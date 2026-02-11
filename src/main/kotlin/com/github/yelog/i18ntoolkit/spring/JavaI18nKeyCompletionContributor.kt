package com.github.yelog.i18ntoolkit.spring

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.util.PsiTreeUtil

/**
 * Provides auto-completion for i18n keys in Java code.
 * Activates inside Spring getMessage() calls and validation annotation message attributes.
 */
class JavaI18nKeyCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val originalPosition = parameters.originalPosition
        val project = position.project

        val contextLiteral = findLiteral(originalPosition ?: position) ?: findLiteral(position) ?: return
        if (!isI18nContext(contextLiteral)) return

        // Completion PSI may run on a copied file where position.containingFile has no VirtualFile.
        val sourceFile = parameters.originalFile.virtualFile ?: position.containingFile?.virtualFile
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val allKeys = sourceFile?.let { cacheService.getAllKeysForModule(it) } ?: cacheService.getAllKeys()
        if (allKeys.isEmpty()) return

        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()
        val caretOffset = parameters.editor.caretModel.offset
        val currentInput = extractCurrentInput(contextLiteral, caretOffset)

        val rankedKeys = allKeys
            .map { key ->
                val translation = resolveTranslation(cacheService, key, sourceFile, displayLocale)
                val typeText = translation?.value?.take(50) ?: ""
                key to scoreKey(key, typeText, currentInput)
            }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        // Disable default prefix filtering and rely on our scoring.
        val fuzzyResult = result.withPrefixMatcher(JavaI18nFuzzyPrefixMatcher(currentInput))

        // Build completion items
        for ((key, _) in rankedKeys) {
            val translation = resolveTranslation(cacheService, key, sourceFile, displayLocale)

            val typeText = translation?.value?.take(50) ?: ""

            val element = LookupElementBuilder.create(key)
                .withTypeText(typeText, true)
                .withIcon(AllIcons.Nodes.Property)

            fuzzyResult.addElement(element)
        }

        super.fillCompletionVariants(parameters, result)
    }

    private fun findLiteral(position: PsiElement): PsiLiteralExpression? {
        return PsiTreeUtil.getParentOfType(position, PsiLiteralExpression::class.java, false)
            ?: position.parent as? PsiLiteralExpression
    }

    private fun isI18nContext(literal: PsiLiteralExpression): Boolean {
        val methodCall = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java)
        val annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java)
        return when {
            methodCall != null -> SpringMessagePatternMatcher.isSpringI18nCall(methodCall)
            annotation != null -> isValidationAnnotationMessage(literal, annotation)
            else -> false
        }
    }

    private fun resolveTranslation(
        cacheService: I18nCacheService,
        key: String,
        sourceFile: com.intellij.openapi.vfs.VirtualFile?,
        locale: String?
    ) = sourceFile?.let { cacheService.getTranslationForModule(key, it, locale) } ?: cacheService.getTranslation(key, locale)

    private fun extractCurrentInput(literal: PsiLiteralExpression, offsetInFile: Int): String {
        val literalValue = literal.value as? String ?: return ""
        val contentStart = literal.textRange.startOffset + 1 // skip opening quote
        val cursorInLiteral = (offsetInFile - contentStart).coerceIn(0, literalValue.length)
        return literalValue.substring(0, cursorInLiteral)
    }

    private fun scoreKey(key: String, translation: String, input: String): Int {
        if (input.isBlank()) return 1

        val keyLower = key.lowercase()
        val inputLower = input.lowercase()
        val translationLower = translation.lowercase()
        var score = 1

        if (keyLower == inputLower) score += 5000
        if (keyLower.startsWith(inputLower)) score += 3000

        val keyContainsPos = keyLower.indexOf(inputLower)
        if (keyContainsPos >= 0) {
            score += 1200
            score += (200 - keyContainsPos).coerceAtLeast(0)
        }

        if (translationLower.contains(inputLower)) score += 400
        if (StringUtil.containsIgnoreCase(key, input)) score += 100

        return score
    }

    private fun isValidationAnnotationMessage(
        literal: PsiLiteralExpression,
        annotation: PsiAnnotation
    ): Boolean {
        val nameValuePair = PsiTreeUtil.getParentOfType(literal, com.intellij.psi.PsiNameValuePair::class.java)
        val attrName = nameValuePair?.name ?: nameValuePair?.attributeName
        return attrName == "message"
    }
}

private class JavaI18nFuzzyPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean = true
    override fun cloneWithPrefix(prefix: String): PrefixMatcher = JavaI18nFuzzyPrefixMatcher(prefix)
}
