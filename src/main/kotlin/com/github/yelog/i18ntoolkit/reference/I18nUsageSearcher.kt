package com.github.yelog.i18ntoolkit.reference

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
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

class I18nUsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val element = queryParameters.elementToSearch
        val project = element.project

        val key = extractKeyFromElement(element, project) ?: return

        val scope = queryParameters.effectiveSearchScope
        searchForKeyUsages(project, key, scope, consumer)
    }

    private fun extractKeyFromElement(element: PsiElement, project: Project): String? {
        return when (element) {
            is JsonProperty -> extractKeyFromJsonProperty(element, project)
            is JSProperty -> extractKeyFromJsProperty(element, project)
            else -> extractKeyFromPropertiesElement(element, project)
        }
    }

    private fun extractKeyFromPropertiesElement(element: PsiElement, project: Project): String? {
        val file = element.containingFile?.virtualFile ?: return null
        if (!file.extension.equals("properties", ignoreCase = true)) return null
        if (!I18nDirectoryScanner.isTranslationFile(file)) return null

        val rawKey = extractPropertiesKey(element) ?: return null
        if (rawKey.isBlank()) return null

        val cacheService = I18nCacheService.getInstance(project)
        val translationFile = cacheService.getTranslationFile(file)
        val prefix = translationFile?.keyPrefix.orEmpty()
        return if (prefix.isEmpty()) rawKey else "$prefix$rawKey"
    }

    private fun extractPropertiesKey(element: PsiElement): String? {
        var current: PsiElement? = element
        while (current != null) {
            invokeStringGetter(current, "getUnescapedKey")?.let { return it }
            invokeStringGetter(current, "getKey")?.let { return it }
            current = current.parent
        }
        return extractPropertiesKeyFromLine(element)
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

    private fun extractPropertiesKeyFromLine(element: PsiElement): String? {
        val fileText = element.containingFile?.text ?: return null
        if (fileText.isEmpty()) return null

        val safeOffset = element.textOffset.coerceIn(0, fileText.length - 1)
        val scanOffset = if (fileText[safeOffset] == '\n' && safeOffset > 0) safeOffset - 1 else safeOffset
        val lineStart = fileText.lastIndexOf('\n', scanOffset).let { if (it < 0) 0 else it + 1 }
        val lineEnd = fileText.indexOf('\n', scanOffset).let { if (it < 0) fileText.length else it }
        if (lineStart > lineEnd) return null
        val line = fileText.substring(lineStart, lineEnd)
        return parsePropertiesKey(line)
    }

    private fun parsePropertiesKey(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null

        var escaped = false
        for (index in trimmed.indices) {
            val c = trimmed[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '=' || c == ':' || c.isWhitespace()) {
                val rawKey = trimmed.substring(0, index).trimEnd()
                return unescapePropertiesText(rawKey)
            }
        }

        return unescapePropertiesText(trimmed)
    }

    private fun unescapePropertiesText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val next = text[i + 1]
                when (next) {
                    't' -> {
                        sb.append('\t')
                        i += 2
                        continue
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                        continue
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                        continue
                    }
                    'f' -> {
                        sb.append('\u000c')
                        i += 2
                        continue
                    }
                    'u', 'U' -> {
                        if (i + 5 < text.length) {
                            val hex = text.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                    }
                }
                sb.append(next)
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun extractKeyFromJsonProperty(property: JsonProperty, project: Project): String? {
        val file = property.containingFile?.virtualFile ?: return null
        if (!I18nDirectoryScanner.isTranslationFile(file)) return null

        val cacheService = I18nCacheService.getInstance(project)
        val translationFile = cacheService.getTranslationFile(file) ?: return null

        return buildFullKey(property, translationFile.keyPrefix)
    }

    private fun extractKeyFromJsProperty(property: JSProperty, project: Project): String? {
        val file = property.containingFile?.virtualFile ?: return null
        if (!I18nDirectoryScanner.isTranslationFile(file)) return null

        val cacheService = I18nCacheService.getInstance(project)
        val translationFile = cacheService.getTranslationFile(file) ?: return null

        return buildFullKey(property, translationFile.keyPrefix)
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

    private fun searchForKeyUsages(
        project: Project,
        key: String,
        scope: SearchScope,
        consumer: Processor<in PsiReference>
    ) {
        val candidateFiles = collectCandidateFiles(project, scope)
        if (candidateFiles.isEmpty()) return

        val psiManager = PsiManager.getInstance(project)
        for (file in candidateFiles) {
            ProgressManager.checkCanceled()
            val shouldContinue = ReadAction.compute<Boolean, RuntimeException> {
                val psiFile = psiManager.findFile(file) ?: return@compute true
                findKeyUsagesInFile(psiFile, key, consumer)
            }
            if (!shouldContinue) {
                return
            }
        }
    }

    private fun findKeyUsagesInFile(
        psiFile: PsiFile,
        key: String,
        consumer: Processor<in PsiReference>
    ): Boolean {
        var shouldContinue = true
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (!shouldContinue) return
                ProgressManager.checkCanceled()

                if (element is JSCallExpression) {
                    shouldContinue = checkCallExpression(element, key, consumer)
                    if (!shouldContinue) return
                }
                if (element is PsiLiteralExpression) {
                    shouldContinue = checkJavaLiteralExpression(element, key, consumer)
                    if (!shouldContinue) return
                }
                super.visitElement(element)
            }
        })
        return shouldContinue
    }

    private fun checkCallExpression(
        callExpr: JSCallExpression,
        key: String,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val methodExpr = callExpr.methodExpression as? JSReferenceExpression ?: return true
        val methodName = methodExpr.referenceName ?: return true

        val i18nFunctions = I18nFunctionResolver.getFunctions(callExpr.project)
        if (!i18nFunctions.contains(methodName)) return true

        val args = callExpr.arguments
        if (args.isEmpty()) return true

        val firstArg = args[0] as? JSLiteralExpression ?: return true
        val partialKey = firstArg.stringValue ?: return true

        // Resolve full key including namespace from useTranslation hook
        val fullKey = I18nNamespaceResolver.getFullKey(callExpr, partialKey)

        // Match if either full key or partial key matches the searched key
        // Also match if searched key ends with the partial key (for namespace-prefixed searches)
        val isMatch = fullKey == key || partialKey == key ||
                (key.contains('.') && key.endsWith(".$partialKey"))

        if (isMatch) {
            val references = firstArg.references
            if (references.isNotEmpty()) {
                for (ref in references) {
                    if (!consumer.process(ref)) {
                        return false
                    }
                }
            } else {
                val relativeRange = TextRange(1, partialKey.length + 1)
                if (!consumer.process(I18nKeyReference(firstArg, relativeRange, fullKey, partialKey))) {
                    return false
                }
            }
        }
        return true
    }

    private fun checkJavaLiteralExpression(
        literal: PsiLiteralExpression,
        key: String,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val stringValue = literal.value as? String ?: return true
        if (stringValue.isBlank()) return true

        val match = SpringMessagePatternMatcher.extractKey(literal) ?: return true
        val partialKey = match.key
        val fullKey = partialKey
        val isMatch = fullKey == key || partialKey == key || (key.contains('.') && key.endsWith(".$partialKey"))
        if (!isMatch) return true

        val references = literal.references
        if (references.isNotEmpty()) {
            for (ref in references) {
                if (!consumer.process(ref)) return false
            }
            return true
        }

        val relativeRange = if (match.matchType == SpringMessagePatternMatcher.MatchType.VALIDATION_ANNOTATION) {
            TextRange(2, 2 + partialKey.length)
        } else {
            TextRange(1, partialKey.length + 1)
        }
        return consumer.process(I18nKeyReference(literal, relativeRange, fullKey, partialKey))
    }

    private fun collectCandidateFiles(project: Project, scope: SearchScope): List<VirtualFile> {
        val indexScope = (scope as? GlobalSearchScope) ?: GlobalSearchScope.projectScope(project)
        val projectFileIndex = ProjectFileIndex.getInstance(project)
        val files = linkedSetOf<VirtualFile>()

        SOURCE_EXTENSIONS.forEach { extension ->
            val indexedFiles = FilenameIndex.getAllFilesByExt(project, extension, indexScope)
            indexedFiles.forEach { file ->
                ProgressManager.checkCanceled()
                if (!scope.contains(file)) return@forEach
                if (!projectFileIndex.isInContent(file)) return@forEach
                if (isExcludedPath(file)) return@forEach
                files.add(file)
            }
        }

        return files.toList()
    }

    private fun isExcludedPath(file: VirtualFile): Boolean {
        val normalizedPath = file.path.replace('\\', '/')
        return EXCLUDED_PATH_SEGMENTS.any { segment -> normalizedPath.contains(segment) }
    }

    companion object {
        private val SOURCE_EXTENSIONS = listOf("js", "jsx", "ts", "tsx", "vue", "mjs", "cjs", "java")
        private val EXCLUDED_PATH_SEGMENTS = listOf("/node_modules/", "/dist/", "/build/")
    }
}
