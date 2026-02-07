package com.github.yelog.i18ntoolkit.spring

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
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
        val project = position.project

        // Walk up to find PsiLiteralExpression
        var literal: PsiLiteralExpression? = null
        var current = position
        var depth = 0
        while (current.parent != null && depth < 5) {
            if (current is PsiLiteralExpression) {
                literal = current
                break
            }
            current = current.parent
            depth++
        }
        if (literal == null) {
            literal = position.parent as? PsiLiteralExpression
        }
        if (literal == null) return

        // Check if inside a Spring i18n method call
        val methodCall = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java)
        val annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java)

        val isI18nContext = when {
            methodCall != null -> SpringMessagePatternMatcher.isSpringI18nCall(methodCall)
            annotation != null -> isValidationAnnotationMessage(literal, annotation)
            else -> false
        }

        if (!isI18nContext) return

        val file = position.containingFile?.virtualFile ?: return
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val allKeys = cacheService.getAllKeysForModule(file)
        if (allKeys.isEmpty()) return

        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Build completion items
        for (key in allKeys.sorted()) {
            val translation = if (displayLocale != null) {
                cacheService.getTranslationForModule(key, file, displayLocale)
            } else {
                cacheService.getTranslationForModule(key, file, null)
            }

            val typeText = translation?.value?.take(50) ?: ""

            val element = LookupElementBuilder.create(key)
                .withTypeText(typeText, true)
                .withIcon(AllIcons.Nodes.Property)

            result.addElement(element)
        }

        super.fillCompletionVariants(parameters, result)
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
