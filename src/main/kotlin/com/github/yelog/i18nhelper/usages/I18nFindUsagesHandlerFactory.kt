package com.github.yelog.i18nhelper.usages

import com.github.yelog.i18nhelper.reference.I18nKeyReference
import com.github.yelog.i18nhelper.scanner.I18nDirectoryScanner
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nNamespaceResolver
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
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Custom Find Usages handler for i18n keys in translation files.
 * This handler overrides the default behavior to search for i18n function calls
 * instead of plain string occurrences.
 */
class I18nFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun canFindUsages(element: PsiElement): Boolean {
        // All PSI access should be in ReadAction
        return ReadAction.compute<Boolean, RuntimeException> {
            // Get the actual property element
            val propertyElement = getPropertyElement(element)
            if (propertyElement == null) {
                thisLogger().info("I18n Helper: canFindUsages=false, element type: ${element.javaClass.simpleName}")
                return@compute false
            }

            val canHandle = isInTranslationFile(propertyElement)
            if (canHandle) {
                thisLogger().info("I18n Helper: canFindUsages=true for property: ${propertyElement.text}")
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
                thisLogger().warn("I18n Helper: No property element found for ${element.javaClass.simpleName}")
                return@compute null
            }

            val key = extractFullKey(propertyElement)
            if (key == null) {
                thisLogger().warn("I18n Helper: Failed to extract key from ${propertyElement.text}")
                return@compute null
            }

            thisLogger().info("I18n Helper: Creating handler for key: $key")
            I18nFindUsagesHandler(propertyElement, key)
        }
    }

    /**
     * Get the property element from the given element.
     * Handles cases where the cursor is on a string literal (the property name).
     */
    private fun getPropertyElement(element: PsiElement): PsiElement? {
        return when (element) {
            is JsonProperty -> element
            is JSProperty -> element
            is JsonStringLiteral -> {
                // If cursor is on the property name string, get the parent property
                element.parent as? JsonProperty
            }
            else -> {
                // Try to find a parent that is a property
                element.parent?.let { getPropertyElement(it) }
            }
        }
    }

    private fun isInTranslationFile(element: PsiElement): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        return I18nDirectoryScanner.isTranslationFile(file)
    }

    private fun extractFullKey(element: PsiElement): String? {
        val project = element.project
        val file = element.containingFile?.virtualFile
        if (file == null) {
            thisLogger().warn("I18n Helper: No virtual file for element")
            return null
        }

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val translationFile = cacheService.getTranslationFile(file)
        if (translationFile == null) {
            thisLogger().warn("I18n Helper: File ${file.path} is not in cache")
            return null
        }

        val fullKey = when (element) {
            is JsonProperty -> buildFullKey(element, translationFile.keyPrefix)
            is JSProperty -> buildFullKey(element, translationFile.keyPrefix)
            else -> {
                thisLogger().warn("I18n Helper: Unexpected element type: ${element.javaClass.simpleName}")
                null
            }
        }

        if (fullKey != null) {
            thisLogger().info("I18n Helper: Extracted key: $fullKey from ${file.name}")
        }

        return fullKey
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
            thisLogger().info("I18n Helper: findReferencesToHighlight called for key: $fullKey")
            val references = findUsagesInScope(searchScope).mapNotNull { it.reference }
            thisLogger().info("I18n Helper: Found ${references.size} references")
            return references
        }

        override fun processElementUsages(
            element: PsiElement,
            processor: Processor<in UsageInfo>,
            options: com.intellij.find.findUsages.FindUsagesOptions
        ): Boolean {
            thisLogger().info("I18n Helper: processElementUsages called for key: $fullKey")
            val usages = findUsagesInScope(options.searchScope)
            thisLogger().info("I18n Helper: Found ${usages.size} usages")

            for (usage in usages) {
                if (!processor.process(usage)) {
                    return false
                }
            }
            return true
        }

        private fun findUsagesInScope(searchScope: com.intellij.psi.search.SearchScope): List<UsageInfo> {
            val usages = mutableListOf<UsageInfo>()
            val project = psiElement.project
            val baseDir = project.guessProjectDir() ?: return usages
            val sourceExtensions = listOf("js", "jsx", "ts", "tsx", "vue", "mjs", "cjs")

            thisLogger().info("I18n Helper: Searching for key '$fullKey' in scope: $searchScope")

            // First, collect all candidate files (without scope check which needs ReadAction)
            val candidateFiles = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
            VfsUtil.iterateChildrenRecursively(baseDir, { file ->
                !file.name.startsWith(".") &&
                file.name != "node_modules" &&
                file.name != "dist" &&
                file.name != "build" &&
                !file.path.contains("/node_modules/")
            }) { file ->
                if (!file.isDirectory && sourceExtensions.contains(file.extension?.lowercase())) {
                    candidateFiles.add(file)
                }
                true
            }

            thisLogger().info("I18n Helper: Found ${candidateFiles.size} candidate files")

            // Then, filter by scope and process all files in ReadAction
            ReadAction.run<RuntimeException> {
                val psiManager = PsiManager.getInstance(project)
                var processedCount = 0

                for (file in candidateFiles) {
                    // Check scope inside ReadAction
                    if (searchScope is GlobalSearchScope && !searchScope.contains(file)) {
                        continue
                    }

                    processedCount++
                    val psiFile = psiManager.findFile(file)
                    if (psiFile != null) {
                        findKeyUsagesInFile(psiFile, usages)
                    }
                }

                thisLogger().info("I18n Helper: Processed $processedCount files in scope")
            }

            thisLogger().info("I18n Helper: Total usages found: ${usages.size}")
            return usages
        }

        private fun findKeyUsagesInFile(
            psiFile: com.intellij.psi.PsiFile,
            usages: MutableList<UsageInfo>
        ) {
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is JSCallExpression) {
                        checkCallExpression(element, usages)
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
                thisLogger().info("I18n Helper: Match found - partial: $partialKey, resolved: $resolvedFullKey, searching: $fullKey")
                // Create a custom reference for this usage
                val relativeRange = com.intellij.openapi.util.TextRange(1, partialKey.length + 1)
                val reference = I18nKeyReference(firstArg, relativeRange, resolvedFullKey, partialKey)
                usages.add(UsageInfo(reference))
            }
        }
    }
}
