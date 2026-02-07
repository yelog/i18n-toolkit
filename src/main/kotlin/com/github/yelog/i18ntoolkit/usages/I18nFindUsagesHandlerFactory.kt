package com.github.yelog.i18ntoolkit.usages

import com.github.yelog.i18ntoolkit.reference.I18nKeyReference
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.spring.SpringMessagePatternMatcher
import com.github.yelog.i18ntoolkit.util.I18nFunctionResolver
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
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
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Custom Find Usages handler for i18n keys in translation files.
 * This handler overrides the default behavior to search for i18n function calls
 * instead of plain string occurrences.
 */
class I18nFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean {
        // All PSI access should be in ReadAction
        return ReadAction.compute<Boolean, RuntimeException> {
            // Get the actual property element
            val propertyElement = getPropertyElement(element)
            if (propertyElement == null) {
                thisLogger().info("I18n Toolkit: canFindUsages=false, element type: ${element.javaClass.simpleName}")
                return@compute false
            }

            val canHandle = isInTranslationFile(propertyElement)
            if (canHandle) {
                thisLogger().info("I18n Toolkit: canFindUsages=true for property: ${propertyElement.text}")
            }

            canHandle
        }
    }

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean
    ): FindUsagesHandler? {
        // All PSI access should be in ReadAction
        return ReadAction.compute<FindUsagesHandler?, RuntimeException> {
            // Get the actual property element
            val propertyElement = getPropertyElement(element)
            if (propertyElement == null) {
                thisLogger().warn("I18n Toolkit: No property element found for ${element.javaClass.simpleName}")
                return@compute null
            }

            val key = extractFullKey(propertyElement)
            if (key == null) {
                thisLogger().warn("I18n Toolkit: Failed to extract key from ${propertyElement.text}")
                return@compute null
            }

            thisLogger().info("I18n Toolkit: Creating handler for key: $key")
            I18nFindUsagesHandler(propertyElement, key)
        }
    }

    /**
     * Get the property element from the given element.
     * Handles cases where the cursor is on a string literal (the property name).
     */
    private fun getPropertyElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is JsonProperty -> return current
                is JSProperty -> return current
                is JsonStringLiteral -> {
                    (current.parent as? JsonProperty)?.let { return it }
                }
            }

            if (extractPropertiesKey(current) != null) {
                return current
            }

            current = current.parent
        }
        return null
    }

    private fun isInTranslationFile(element: PsiElement): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        return I18nDirectoryScanner.isTranslationFile(file)
    }

    private fun extractFullKey(element: PsiElement): String? {
        val project = element.project
        val file = element.containingFile?.virtualFile
        if (file == null) {
            thisLogger().warn("I18n Toolkit: No virtual file for element")
            return null
        }

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val translationFile = cacheService.getTranslationFile(file)
        if (translationFile == null) {
            thisLogger().warn("I18n Toolkit: File ${file.path} is not in cache")
            return null
        }

        val fullKey = when (element) {
            is JsonProperty -> buildFullKey(element, translationFile.keyPrefix)
            is JSProperty -> buildFullKey(element, translationFile.keyPrefix)
            else -> {
                val rawKey = extractPropertiesKey(element)
                if (rawKey.isNullOrBlank()) {
                    thisLogger().warn("I18n Toolkit: Unexpected element type: ${element.javaClass.simpleName}")
                    null
                } else if (translationFile.keyPrefix.isEmpty()) {
                    rawKey
                } else {
                    "${translationFile.keyPrefix}$rawKey"
                }
            }
        }

        if (fullKey != null) {
            thisLogger().info("I18n Toolkit: Extracted key: $fullKey from ${file.name}")
        }

        return fullKey
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
        val lineStart = fileText.lastIndexOf('\n', safeOffset).let { if (it < 0) 0 else it + 1 }
        val lineEnd = fileText.indexOf('\n', safeOffset).let { if (it < 0) fileText.length else it }
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

    /**
     * Custom handler that searches for i18n function calls using the full key.
     */
    private inner class I18nFindUsagesHandler(
        element: PsiElement,
        private val fullKey: String
    ) : FindUsagesHandler(element) {

        override fun getPrimaryElements(): Array<PsiElement> {
            return arrayOf(psiElement)
        }

        override fun findReferencesToHighlight(
            target: PsiElement,
            searchScope: com.intellij.psi.search.SearchScope
        ): Collection<PsiReference> {
            thisLogger().info("I18n Toolkit: findReferencesToHighlight called for key: $fullKey")
            val references = findUsagesInScope(searchScope).mapNotNull { it.reference }
            thisLogger().info("I18n Toolkit: Found ${references.size} references")
            return references
        }

        override fun processElementUsages(
            element: PsiElement,
            processor: Processor<in UsageInfo>,
            options: com.intellij.find.findUsages.FindUsagesOptions
        ): Boolean {
            thisLogger().info("I18n Toolkit: processElementUsages called for key: $fullKey")
            val usages = findUsagesInScope(options.searchScope)
            thisLogger().info("I18n Toolkit: Found ${usages.size} usages")

            for (usage in usages) {
                if (!processor.process(usage)) {
                    return false
                }
            }
            return true
        }

        private fun findUsagesInScope(searchScope: SearchScope): List<UsageInfo> {
            val usages = mutableListOf<UsageInfo>()
            val project = psiElement.project
            val candidateFiles = collectCandidateFiles(project, searchScope)

            thisLogger().info("I18n Toolkit: Searching for key '$fullKey' in scope: $searchScope")

            thisLogger().info("I18n Toolkit: Found ${candidateFiles.size} candidate files")

            val psiManager = PsiManager.getInstance(project)
            var processedCount = 0

            for (file in candidateFiles) {
                ProgressManager.checkCanceled()
                ReadAction.run<RuntimeException> {
                    val psiFile = psiManager.findFile(file) ?: return@run
                    processedCount++
                    findKeyUsagesInFile(psiFile, usages)
                }
            }

            thisLogger().info("I18n Toolkit: Processed $processedCount files in scope")
            thisLogger().info("I18n Toolkit: Total usages found: ${usages.size}")
            return usages
        }

        private fun findKeyUsagesInFile(
            psiFile: PsiFile,
            usages: MutableList<UsageInfo>
        ) {
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()
                    if (element is JSCallExpression) {
                        checkCallExpression(element, usages)
                    }
                    if (element is PsiLiteralExpression) {
                        checkJavaLiteralExpression(element, usages)
                    }
                    super.visitElement(element)
                }
            })
        }

        private fun checkCallExpression(
            callExpr: JSCallExpression,
            usages: MutableList<UsageInfo>
        ) {
            val methodExpr = callExpr.methodExpression as? JSReferenceExpression ?: return
            val methodName = methodExpr.referenceName ?: return

            val i18nFunctions = I18nFunctionResolver.getFunctions(psiElement.project)
            if (!i18nFunctions.contains(methodName)) return

            val args = callExpr.arguments
            if (args.isEmpty()) return

            val firstArg = args[0] as? JSLiteralExpression ?: return
            val partialKey = firstArg.stringValue ?: return

            // Resolve full key including namespace from useTranslation hook
            val resolvedFullKey = I18nNamespaceResolver.getFullKey(callExpr, partialKey)

            // Match if either resolved full key matches, or if partial key matches
            // Also match if searched key ends with the partial key (for namespace-prefixed searches)
            val isMatch = resolvedFullKey == fullKey || partialKey == fullKey ||
                    (fullKey.contains('.') && fullKey.endsWith(".$partialKey"))

            if (isMatch) {
                thisLogger().info("I18n Toolkit: Match found - partial: $partialKey, resolved: $resolvedFullKey, searching: $fullKey")
                // Create a custom reference for this usage
                val relativeRange = TextRange(1, partialKey.length + 1)
                val reference = I18nKeyReference(firstArg, relativeRange, resolvedFullKey, partialKey)
                usages.add(UsageInfo(reference))
            }
        }

        private fun checkJavaLiteralExpression(
            literal: PsiLiteralExpression,
            usages: MutableList<UsageInfo>
        ) {
            val stringValue = literal.value as? String ?: return
            if (stringValue.isBlank()) return

            val match = SpringMessagePatternMatcher.extractKey(literal) ?: return
            val partialKey = match.key
            val resolvedFullKey = partialKey

            val isMatch = resolvedFullKey == fullKey || partialKey == fullKey ||
                    (fullKey.contains('.') && fullKey.endsWith(".$partialKey"))
            if (!isMatch) return

            thisLogger().info(
                "I18n Toolkit: Java match found - partial: $partialKey, resolved: $resolvedFullKey, searching: $fullKey"
            )

            val relativeRange = if (match.matchType == SpringMessagePatternMatcher.MatchType.VALIDATION_ANNOTATION) {
                TextRange(2, 2 + partialKey.length)
            } else {
                TextRange(1, partialKey.length + 1)
            }
            val reference = I18nKeyReference(literal, relativeRange, resolvedFullKey, partialKey)
            usages.add(UsageInfo(reference))
        }
    }

    private fun collectCandidateFiles(project: Project, searchScope: SearchScope): List<VirtualFile> {
        val indexScope = (searchScope as? GlobalSearchScope) ?: GlobalSearchScope.projectScope(project)
        val projectFileIndex = ProjectFileIndex.getInstance(project)
        val files = linkedSetOf<VirtualFile>()

        SOURCE_EXTENSIONS.forEach { extension ->
            val indexedFiles = FilenameIndex.getAllFilesByExt(project, extension, indexScope)
            indexedFiles.forEach { file ->
                ProgressManager.checkCanceled()
                if (!searchScope.contains(file)) return@forEach
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
