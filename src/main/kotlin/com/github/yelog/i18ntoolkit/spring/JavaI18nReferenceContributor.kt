package com.github.yelog.i18ntoolkit.spring

import com.github.yelog.i18ntoolkit.reference.I18nKeyReference
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class JavaI18nReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            JavaI18nReferenceProvider()
        )
    }
}

class JavaI18nReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY

        val match = SpringMessagePatternMatcher.extractKey(literal) ?: return PsiReference.EMPTY_ARRAY
        val key = match.key

        // For validation annotations, the value includes {}, so adjust the text range
        val stringValue = literal.value as? String ?: return PsiReference.EMPTY_ARRAY
        val textRange = if (match.matchType == SpringMessagePatternMatcher.MatchType.VALIDATION_ANNOTATION) {
            // "{key}" → key starts at offset 2 (quote + {), length is key.length
            TextRange(2, 2 + key.length)
        } else {
            TextRange(1, stringValue.length + 1)
        }

        return arrayOf(I18nKeyReference(literal, textRange, key))
    }
}
