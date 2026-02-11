package com.github.yelog.i18ntoolkit.rename

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.model.TranslationFileType
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.spring.SpringMessagePatternMatcher
import com.github.yelog.i18ntoolkit.util.I18nFunctionResolver
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo

class I18nKeyRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean {
        return resolveRenameContext(element) != null
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val context = resolveRenameContext(element)
        if (context == null || newName.isBlank()) {
            super.renameElement(element, newName, usages, listener)
            return
        }

        val oldFullKey = context.fullKey
        val newFullKey = context.toNewFullKey(newName)
        if (newFullKey == oldFullKey) {
            listener?.elementRenamed(context.sourceElement)
            return
        }

        val project = element.project

        WriteCommandAction.runWriteCommandAction(project, "Rename i18n key", null, Runnable {
            renameTranslationDeclarations(project, oldFullKey, newFullKey)
            renameCodeUsagesByScan(project, oldFullKey, newFullKey)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
        })

        I18nCacheService.getInstance(project).refresh()
        listener?.elementRenamed(context.sourceElement)
    }

    private fun renameTranslationDeclarations(project: Project, oldFullKey: String, newFullKey: String) {
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val entries = cacheService.getAllTranslations(oldFullKey).values.toList()
        for (entry in entries) {
            val translationFile = cacheService.getTranslationFile(entry.file)
            val prefix = translationFile?.keyPrefix.orEmpty()
            val oldLocalKey = removePrefixIfPresent(oldFullKey, prefix)
            val newLocalKey = removePrefixIfPresent(newFullKey, prefix)

            val fileType = TranslationFileType.fromExtension(entry.file.extension ?: "") ?: continue
            when (fileType) {
                TranslationFileType.JSON -> renameJsonProperty(project, entry, newLocalKey.substringAfterLast('.'))
                TranslationFileType.JAVASCRIPT,
                TranslationFileType.TYPESCRIPT -> renameJsProperty(project, entry, newLocalKey.substringAfterLast('.'))
                TranslationFileType.PROPERTIES -> renamePropertiesKey(entry, newLocalKey)
                TranslationFileType.YAML -> renameYamlKey(entry, oldLocalKey, newLocalKey)
                TranslationFileType.TOML -> renameTomlKey(entry, oldLocalKey, newLocalKey)
            }
        }
    }

    private fun renameCodeUsages(
        project: Project,
        usages: Array<out UsageInfo>,
        oldFullKey: String,
        newFullKey: String
    ) {
        val updatedRanges = mutableSetOf<String>()

        for (usage in usages) {
            val reference = usage.reference ?: continue
            val refElement = reference.element

            when (refElement) {
                is JSLiteralExpression -> {
                    val marker = "${refElement.containingFile.virtualFile?.path}:${refElement.textRange.startOffset}:${refElement.textRange.endOffset}"
                    if (!updatedRanges.add(marker)) continue
                    renameJsUsage(refElement, oldFullKey, newFullKey)
                }
                is PsiLiteralExpression -> {
                    val marker = "${refElement.containingFile.virtualFile?.path}:${refElement.textRange.startOffset}:${refElement.textRange.endOffset}"
                    if (!updatedRanges.add(marker)) continue
                    renameJavaUsage(refElement, oldFullKey, newFullKey)
                }
            }
        }
    }

    private fun renameCodeUsagesByScan(project: Project, oldFullKey: String, newFullKey: String) {
        val psiManager = PsiManager.getInstance(project)
        val baseDir = project.guessProjectDir() ?: return
        val candidates = linkedSetOf<VirtualFile>()
        VfsUtil.iterateChildrenRecursively(baseDir, ::shouldTraverse) { file ->
            if (!file.isDirectory && file.extension?.lowercase() in SOURCE_EXTENSIONS) {
                candidates.add(file)
            }
            true
        }

        val jsFunctions = I18nFunctionResolver.getFunctions(project)

        for (file in candidates) {
            val psiFile = psiManager.findFile(file) ?: continue
            val jsTargets = mutableListOf<Pair<JSLiteralExpression, String>>()
            val javaTargets = mutableListOf<Pair<PsiLiteralExpression, String>>()

            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    when (element) {
                        is JSCallExpression -> {
                            val methodExpr = element.methodExpression as? JSReferenceExpression
                            val methodName = methodExpr?.referenceName
                            if (methodName != null && jsFunctions.contains(methodName)) {
                                val firstArg = element.arguments.firstOrNull() as? JSLiteralExpression
                                val partial = firstArg?.stringValue
                                if (!partial.isNullOrBlank()) {
                                    val resolved = I18nNamespaceResolver.getFullKey(element, partial)
                                    val isDirectMatch = resolved == oldFullKey
                                    val isSuffixMatch = oldFullKey.contains('.') && oldFullKey.endsWith(".$partial")
                                    if (isDirectMatch || isSuffixMatch) {
                                        val namespacePrefix = when {
                                            isDirectMatch && resolved.endsWith(partial) -> resolved.removeSuffix(partial)
                                            isSuffixMatch -> oldFullKey.removeSuffix(partial)
                                            else -> ""
                                        }
                                        val newLiteral = if (namespacePrefix.isNotEmpty() && newFullKey.startsWith(namespacePrefix)) {
                                            newFullKey.removePrefix(namespacePrefix)
                                        } else {
                                            newFullKey
                                        }
                                        jsTargets.add(firstArg to newLiteral)
                                    }
                                }
                            }
                        }
                        is PsiLiteralExpression -> {
                            val match = SpringMessagePatternMatcher.extractKey(element)
                            if (match != null && match.key == oldFullKey) {
                                val replacement = if (match.matchType == SpringMessagePatternMatcher.MatchType.VALIDATION_ANNOTATION) {
                                    "{$newFullKey}"
                                } else {
                                    newFullKey
                                }
                                javaTargets.add(element to replacement)
                            }
                        }
                    }
                    super.visitElement(element)
                }
            })

            jsTargets.forEach { (literal, value) -> replaceJsLiteral(literal, value) }
            javaTargets.forEach { (literal, value) -> replaceJavaLiteral(literal, value) }

            val extension = file.extension?.lowercase()
            if (extension != null && extension != "java") {
                replaceByRegexFallback(project, psiFile, jsFunctions, oldFullKey, newFullKey)
            }
        }
    }

    private fun renameJsUsage(literal: JSLiteralExpression, oldFullKey: String, newFullKey: String) {
        val oldLiteral = literal.stringValue ?: return
        val callExpr = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return
        val resolvedFullKey = I18nNamespaceResolver.getFullKey(callExpr, oldLiteral)
        val isDirectMatch = resolvedFullKey == oldFullKey
        val isSuffixMatch = oldFullKey.contains('.') && oldFullKey.endsWith(".$oldLiteral")
        if (!isDirectMatch && !isSuffixMatch) return

        val namespacePrefix = when {
            isDirectMatch && resolvedFullKey.endsWith(oldLiteral) -> resolvedFullKey.removeSuffix(oldLiteral)
            isSuffixMatch -> oldFullKey.removeSuffix(oldLiteral)
            else -> ""
        }

        val newLiteral = if (namespacePrefix.isNotEmpty() && newFullKey.startsWith(namespacePrefix)) {
            newFullKey.removePrefix(namespacePrefix)
        } else {
            newFullKey
        }

        replaceJsLiteral(literal, newLiteral)
    }

    private fun renameJavaUsage(literal: PsiLiteralExpression, oldFullKey: String, newFullKey: String) {
        val match = SpringMessagePatternMatcher.extractKey(literal) ?: return
        if (match.key != oldFullKey) return

        val newLiteral = if (match.matchType == SpringMessagePatternMatcher.MatchType.VALIDATION_ANNOTATION) {
            "{$newFullKey}"
        } else {
            newFullKey
        }

        replaceJavaLiteral(literal, newLiteral)
    }

    private fun renameJsonProperty(project: Project, entry: TranslationEntry, newLeaf: String) {
        val psiFile = PsiManager.getInstance(project).findFile(entry.file) ?: return
        val element = psiFile.findElementAt(entry.offset) ?: return
        var current: PsiElement? = element
        while (current != null && current !is JsonProperty) {
            current = current.parent
        }
        val property = current ?: return
        property.setName(newLeaf)
    }

    private fun renameJsProperty(project: Project, entry: TranslationEntry, newLeaf: String) {
        val psiFile = PsiManager.getInstance(project).findFile(entry.file) ?: return
        val element = psiFile.findElementAt(entry.offset) ?: return
        var current: PsiElement? = element
        while (current != null && current !is JSProperty) {
            current = current.parent
        }
        val property = current ?: return
        property.setName(newLeaf)
    }

    private fun renamePropertiesKey(entry: TranslationEntry, newLocalKey: String) {
        val document = FileDocumentManager.getInstance().getDocument(entry.file) ?: return
        val lineNumber = document.getLineNumber(entry.offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(TextRange(lineStart, lineEnd))

        val separatorIndex = findPropertySeparator(lineText)
        if (separatorIndex <= 0) return

        val keyRangeStart = lineStart
        val keyRangeEnd = lineStart + separatorIndex
        document.replaceString(keyRangeStart, keyRangeEnd, newLocalKey)
        FileDocumentManager.getInstance().saveDocument(document)
    }

    private fun renameYamlKey(entry: TranslationEntry, oldLocalKey: String, newLocalKey: String) {
        val document = FileDocumentManager.getInstance().getDocument(entry.file) ?: return
        val content = document.text
        val replaced = replaceYamlKey(content, oldLocalKey, newLocalKey) ?: return
        if (replaced != content) {
            document.setText(replaced)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    private fun renameTomlKey(entry: TranslationEntry, oldLocalKey: String, newLocalKey: String) {
        val document = FileDocumentManager.getInstance().getDocument(entry.file) ?: return
        val content = document.text
        val replaced = replaceTomlKey(content, oldLocalKey, newLocalKey) ?: return
        if (replaced != content) {
            document.setText(replaced)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    private fun replaceJsLiteral(literal: JSLiteralExpression, newLiteral: String) {
        val document = PsiDocumentManager.getInstance(literal.project).getDocument(literal.containingFile) ?: return
        val literalText = literal.text
        val quote = if (literalText.startsWith("'")) '\'' else '"'
        val escaped = if (quote == '\'') {
            newLiteral
                .replace("\\", "\\\\")
                .replace("'", "\\'")
        } else {
            newLiteral
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        }

        document.replaceString(
            literal.textRange.startOffset,
            literal.textRange.endOffset,
            "$quote$escaped$quote"
        )
    }

    private fun replaceJavaLiteral(literal: PsiLiteralExpression, newLiteral: String) {
        val document = PsiDocumentManager.getInstance(literal.project).getDocument(literal.containingFile) ?: return
        val escaped = newLiteral
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        document.replaceString(
            literal.textRange.startOffset,
            literal.textRange.endOffset,
            "\"$escaped\""
        )
    }

    private fun replaceByRegexFallback(
        project: Project,
        psiFile: com.intellij.psi.PsiFile,
        jsFunctions: Set<String>,
        oldFullKey: String,
        newFullKey: String
    ) {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
        var updated = document.text
        val oldLeaf = oldFullKey.substringAfterLast('.')
        val newLeaf = newFullKey.substringAfterLast('.')

        for (function in jsFunctions) {
            if (function.isBlank()) continue
            val escapedFunction = Regex.escape(function)

            val fullKeyPattern = Regex("""($escapedFunction\s*\(\s*['"])${Regex.escape(oldFullKey)}(['"])""")
            updated = fullKeyPattern.replace(updated) { match ->
                "${match.groupValues[1]}$newFullKey${match.groupValues[2]}"
            }

            val leafPattern = Regex("""($escapedFunction\s*\(\s*['"])${Regex.escape(oldLeaf)}(['"])""")
            updated = leafPattern.replace(updated) { match ->
                "${match.groupValues[1]}$newLeaf${match.groupValues[2]}"
            }
        }

        if (updated != document.text) {
            document.setText(updated)
        }
    }

    private fun resolveRenameContext(element: PsiElement): RenameContext? {
        val project = element.project
        val sourceElement = findTranslationElement(element) ?: return null
        val file = sourceElement.containingFile?.virtualFile ?: return null
        if (!I18nDirectoryScanner.isTranslationFile(file)) return null

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()
        val translationFile = cacheService.getTranslationFile(file) ?: return null

        val fullKey = when (sourceElement) {
            is JsonProperty -> buildFullKey(sourceElement, translationFile.keyPrefix)
            is JSProperty -> buildFullKey(sourceElement, translationFile.keyPrefix)
            else -> {
                val key = extractPropertiesKey(sourceElement) ?: return null
                if (translationFile.keyPrefix.isEmpty()) key else "${translationFile.keyPrefix}$key"
            }
        }

        if (cacheService.getAllTranslations(fullKey).isEmpty()) return null

        val mode = if (sourceElement is JsonProperty || sourceElement is JSProperty) {
            RenameMode.RENAME_LAST_SEGMENT
        } else {
            RenameMode.RENAME_FULL_LOCAL_KEY
        }

        return RenameContext(sourceElement, fullKey, translationFile.keyPrefix, mode)
    }

    private fun findTranslationElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is JsonProperty -> return current
                is JSProperty -> return current
            }

            if (extractPropertiesKey(current) != null) {
                return current
            }

            current = current.parent
        }
        return null
    }

    private fun extractPropertiesKey(element: PsiElement): String? {
        val file = element.containingFile?.virtualFile ?: return null
        if (!file.extension.equals("properties", ignoreCase = true)) return null
        if (!I18nDirectoryScanner.isTranslationFile(file)) return null

        var current: PsiElement? = element
        while (current != null) {
            invokeStringGetter(current, "getUnescapedKey")?.let { return it }
            invokeStringGetter(current, "getKey")?.let { return it }
            current = current.parent
        }

        val text = element.containingFile?.text ?: return null
        if (text.isEmpty()) return null

        val safeOffset = element.textOffset.coerceIn(0, text.length - 1)
        val scanOffset = if (text[safeOffset] == '\n' && safeOffset > 0) safeOffset - 1 else safeOffset
        val lineStart = text.lastIndexOf('\n', scanOffset).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', scanOffset).let { if (it < 0) text.length else it }
        if (lineStart > lineEnd) return null

        val line = text.substring(lineStart, lineEnd)
        val separator = findPropertySeparator(line)
        if (separator <= 0) return null
        return line.substring(0, separator).trim()
    }

    private fun invokeStringGetter(target: Any, methodName: String): String? {
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: return null
            method.invoke(target) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildFullKey(property: PsiElement, prefix: String): String {
        val keyParts = mutableListOf<String>()
        var current: PsiElement? = property

        while (current != null) {
            when (current) {
                is JsonProperty -> {
                    keyParts.add(0, current.name)
                    current = current.parent?.parent
                }
                is JSProperty -> {
                    current.name?.let { keyParts.add(0, it) }
                    current = current.parent?.parent
                }
                else -> current = current.parent
            }
        }

        val key = keyParts.joinToString(".")
        return if (prefix.isEmpty()) key else "$prefix$key"
    }

    private fun removePrefixIfPresent(value: String, prefix: String): String {
        return if (prefix.isNotEmpty() && value.startsWith(prefix)) value.removePrefix(prefix) else value
    }

    private fun findPropertySeparator(line: String): Int {
        var escaped = false
        for (i in line.indices) {
            val ch = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> escaped = true
                '=', ':' -> return i
            }
        }

        escaped = false
        for (i in line.indices) {
            val ch = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch.isWhitespace()) return i
        }
        return -1
    }

    private fun replaceYamlKey(content: String, oldLocalKey: String, newLocalKey: String): String? {
        val lines = content.split("\n").toMutableList()
        val stack = mutableListOf<Pair<Int, String>>()
        val newLeaf = newLocalKey.substringAfterLast('.')

        for (index in lines.indices) {
            val parsed = parseYamlLine(lines[index]) ?: continue
            while (stack.isNotEmpty() && parsed.indent <= stack.last().first) {
                stack.removeAt(stack.lastIndex)
            }

            val currentPath = (stack.map { it.second } + parsed.normalizedKey).joinToString(".")
            if (currentPath == oldLocalKey) {
                val rebuiltKey = rebuildKeyToken(parsed.rawKey, newLeaf)
                lines[index] = "${" ".repeat(parsed.indent)}$rebuiltKey:${parsed.valuePart}"
                return lines.joinToString("\n")
            }

            val valuePart = parsed.valuePart.trim()
            if (valuePart.isEmpty() || valuePart.startsWith("#")) {
                stack.add(parsed.indent to parsed.normalizedKey)
            }
        }

        return null
    }

    private fun replaceTomlKey(content: String, oldLocalKey: String, newLocalKey: String): String? {
        val lines = content.split("\n").toMutableList()
        val newLeaf = newLocalKey.substringAfterLast('.')
        var currentTable = ""

        for (index in lines.indices) {
            val table = parseTomlTable(lines[index])
            if (table != null) {
                currentTable = table
                continue
            }

            val parsed = parseTomlLine(lines[index]) ?: continue
            val currentPath = if (currentTable.isBlank()) {
                parsed.normalizedKey
            } else {
                "$currentTable.${parsed.normalizedKey}"
            }

            if (currentPath == oldLocalKey) {
                val rebuiltKey = rebuildKeyToken(parsed.rawKey, newLeaf)
                lines[index] = "${" ".repeat(parsed.indent)}$rebuiltKey =${parsed.valuePart}"
                return lines.joinToString("\n")
            }
        }

        return null
    }

    private fun parseYamlLine(line: String): ParsedLine? {
        if (line.isBlank()) return null
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("#")) return null

        val separatorIndex = findSeparatorOutsideQuotes(line, ':')
        if (separatorIndex <= 0) return null

        val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (separatorIndex <= indent) return null

        val rawKey = line.substring(indent, separatorIndex).trimEnd()
        val normalizedKey = rawKey.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
        if (normalizedKey.isBlank()) return null

        val valuePart = line.substring(separatorIndex + 1)
        return ParsedLine(indent, rawKey, normalizedKey, valuePart)
    }

    private fun parseTomlTable(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("[[")) {
            val closing = trimmed.indexOf("]]", 2)
            if (closing > 2) return trimmed.substring(2, closing).trim()
        }
        if (trimmed.startsWith("[")) {
            val closing = trimmed.indexOf(']', 1)
            if (closing > 1) return trimmed.substring(1, closing).trim()
        }
        return null
    }

    private fun parseTomlLine(line: String): ParsedLine? {
        if (line.isBlank()) return null
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("#")) return null

        val separatorIndex = findSeparatorOutsideQuotes(line, '=')
        if (separatorIndex <= 0) return null

        val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (separatorIndex <= indent) return null

        val rawKey = line.substring(indent, separatorIndex).trimEnd()
        val normalizedKey = rawKey.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
        if (normalizedKey.isBlank()) return null

        val valuePart = line.substring(separatorIndex + 1)
        return ParsedLine(indent, rawKey, normalizedKey, valuePart)
    }

    private fun findSeparatorOutsideQuotes(line: String, separator: Char): Int {
        var inSingle = false
        var inDouble = false
        var escaped = false

        for (index in line.indices) {
            val ch = line[index]
            if (escaped) {
                escaped = false
                continue
            }

            if (ch == '\\' && inDouble) {
                escaped = true
                continue
            }

            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }

            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }

            if (!inSingle && !inDouble && ch == separator) {
                return index
            }
        }
        return -1
    }

    private fun rebuildKeyToken(rawKey: String, newLeaf: String): String {
        val trimmed = rawKey.trim()
        return when {
            trimmed.startsWith("\"") && trimmed.endsWith("\"") -> "\"${newLeaf.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            trimmed.startsWith("'") && trimmed.endsWith("'") -> "'${newLeaf.replace("'", "''")}'"
            else -> newLeaf
        }
    }

    private enum class RenameMode {
        RENAME_LAST_SEGMENT,
        RENAME_FULL_LOCAL_KEY
    }

    private data class RenameContext(
        val sourceElement: PsiElement,
        val fullKey: String,
        val keyPrefix: String,
        val mode: RenameMode
    ) {
        fun toNewFullKey(newName: String): String {
            return when (mode) {
                RenameMode.RENAME_LAST_SEGMENT -> {
                    val parent = fullKey.substringBeforeLast('.', missingDelimiterValue = "")
                    if (parent.isEmpty()) newName else "$parent.$newName"
                }
                RenameMode.RENAME_FULL_LOCAL_KEY -> {
                    if (keyPrefix.isNotEmpty()) "$keyPrefix$newName" else newName
                }
            }
        }
    }

    private data class ParsedLine(
        val indent: Int,
        val rawKey: String,
        val normalizedKey: String,
        val valuePart: String
    )

    companion object {
        private val SOURCE_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "vue", "mjs", "cjs", "java")
        private val EXCLUDED_PATH_SEGMENTS = listOf("/node_modules/", "/dist/", "/build/")

        private fun shouldTraverse(file: VirtualFile): Boolean {
            if (!file.isDirectory) return true
            val normalizedPath = file.path.replace('\\', '/')
            return EXCLUDED_PATH_SEGMENTS.none { normalizedPath.contains(it) } && !file.name.startsWith(".")
        }
    }
}
