package com.github.yelog.i18ntoolkit.spring

import com.github.yelog.i18ntoolkit.quickfix.CreateI18nKeyQuickFix
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression

/**
 * Annotator that highlights missing i18n keys in Java files as errors.
 */
class JavaI18nKeyAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiLiteralExpression) return
        if (element.value !is String) return

        val match = SpringMessagePatternMatcher.extractKey(element) ?: return
        val key = match.key

        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val file = element.containingFile?.virtualFile ?: return
        val translations = cacheService.getAllTranslationsForModule(key, file)
        if (translations.isNotEmpty()) return

        // Key doesn't exist - create error annotation
        val elementRange = element.textRange
        val startOffset = elementRange.startOffset

        val (keyStartOffset, keyEndOffset) = if (match.matchType == SpringMessagePatternMatcher.MatchType.VALIDATION_ANNOTATION) {
            // For "{key}", highlight just the key part (skip quote and {)
            Pair(startOffset + 2, startOffset + 2 + key.length)
        } else {
            // For "key", highlight just the key part (skip quote)
            Pair(startOffset + 1, startOffset + 1 + key.length)
        }

        val highlightRange = TextRange(keyStartOffset, keyEndOffset)
        val message = "Unresolved i18n key: '$key'"

        holder.newAnnotation(HighlightSeverity.ERROR, message)
            .range(highlightRange)
            .textAttributes(DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)
            .withFix(CreateI18nKeyQuickFix(key, key))
            .create()
    }
}
