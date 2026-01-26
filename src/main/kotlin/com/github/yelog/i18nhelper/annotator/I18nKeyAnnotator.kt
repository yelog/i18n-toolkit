package com.github.yelog.i18nhelper.annotator

import com.github.yelog.i18nhelper.quickfix.CreateI18nKeyQuickFix
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nNamespaceResolver
import com.github.yelog.i18nhelper.util.I18nFunctionResolver
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Annotator that highlights missing i18n keys as errors.
 * Shows diagnostic errors similar to syntax errors when an i18n key doesn't exist.
 */
class I18nKeyAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only process call expressions
        if (element !is JSCallExpression) return

        // Check if it's an i18n function call
        val methodExpr = element.methodExpression as? JSReferenceExpression ?: return
        val methodName = methodExpr.referenceName ?: return

        val i18nFunctions = I18nFunctionResolver.getFunctions(element.project)
        if (!i18nFunctions.contains(methodName)) return

        // Get the first argument (the key)
        val args = element.arguments
        if (args.isEmpty()) return

        val firstArg = args[0] as? JSLiteralExpression ?: return
        val partialKey = firstArg.stringValue ?: return
        if (partialKey.isBlank()) return

        // Resolve the full key including namespace
        val fullKey = I18nNamespaceResolver.getFullKey(element, partialKey)

        // Check if the key exists in cache
        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val translations = cacheService.getAllTranslations(fullKey)

        // If no translations found for the full key, also try the partial key
        if (translations.isEmpty()) {
            val partialTranslations = cacheService.getAllTranslations(partialKey)
            if (partialTranslations.isEmpty()) {
                // Key doesn't exist - create error annotation
                highlightMissingKey(firstArg, partialKey, fullKey, holder)
            }
        }
    }

    private fun highlightMissingKey(
        literalExpr: JSLiteralExpression,
        partialKey: String,
        fullKey: String,
        holder: AnnotationHolder
    ) {
        // Calculate the range of the string content (excluding quotes)
        val elementRange = literalExpr.textRange
        val startOffset = elementRange.startOffset

        // The string literal includes quotes, so we need to highlight just the content
        // Offset by 1 to skip the opening quote
        val keyStartOffset = startOffset + 1
        val keyEndOffset = keyStartOffset + partialKey.length
        val highlightRange = TextRange(keyStartOffset, keyEndOffset)

        // Create error message
        val message = if (fullKey == partialKey) {
            "Unresolved i18n key: '$partialKey'"
        } else {
            "Unresolved i18n key: '$fullKey' (partial: '$partialKey')"
        }

        // Create error annotation with quick fix
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            .range(highlightRange)
            .textAttributes(DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)
            .withFix(CreateI18nKeyQuickFix(fullKey, if (fullKey == partialKey) partialKey else "$fullKey ($partialKey)"))
            .create()
    }
}
