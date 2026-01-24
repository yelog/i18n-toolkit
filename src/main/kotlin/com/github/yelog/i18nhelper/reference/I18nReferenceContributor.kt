package com.github.yelog.i18nhelper.reference

import com.github.yelog.i18nhelper.model.TranslationEntry
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class I18nReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            I18nReferenceProvider()
        )
    }
}

class I18nReferenceProvider : PsiReferenceProvider() {

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val literal = element as? JSLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        val stringValue = literal.stringValue ?: return PsiReference.EMPTY_ARRAY

        if (!isI18nFunctionArgument(literal)) {
            return PsiReference.EMPTY_ARRAY
        }

        val textRange = TextRange(1, stringValue.length + 1)
        return arrayOf(I18nKeyReference(literal, textRange, stringValue))
    }

    private fun isI18nFunctionArgument(literal: JSLiteralExpression): Boolean {
        val callExpr = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return false
        val methodExpr = callExpr.methodExpression as? JSReferenceExpression ?: return false
        val methodName = methodExpr.referenceName ?: return false

        if (!i18nFunctions.contains(methodName)) return false

        val args = callExpr.arguments
        return args.isNotEmpty() && args[0] == literal
    }
}

class I18nKeyReference(
    element: PsiElement,
    textRange: TextRange,
    private val key: String
) : PsiReferenceBase<PsiElement>(element, textRange), PsiPolyVariantReference {

    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        return if (results.size == 1) results[0].element else null
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        val entries = cacheService.getAllTranslations(key)

        return entries.values.mapNotNull { entry ->
            findPsiElement(entry)?.let { PsiElementResolveResult(it) }
        }.toTypedArray()
    }

    private fun findPsiElement(entry: TranslationEntry): PsiElement? {
        val psiFile = PsiManager.getInstance(element.project).findFile(entry.file) ?: return null
        return psiFile.findElementAt(entry.offset)
    }

    override fun getVariants(): Array<Any> {
        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        return cacheService.getAllKeys().toTypedArray()
    }
}
