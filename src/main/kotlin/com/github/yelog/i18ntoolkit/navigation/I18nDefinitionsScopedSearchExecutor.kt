package com.github.yelog.i18ntoolkit.navigation

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nFunctionResolver
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

class I18nDefinitionsScopedSearchExecutor : QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {

    override fun execute(
        queryParameters: DefinitionsScopedSearch.SearchParameters,
        consumer: Processor<in PsiElement>
    ): Boolean {
        val element = queryParameters.element
        val literal = element as? JSLiteralExpression ?: return true

        val callExpression = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java)
            ?: return true
        val methodExpr = callExpression.methodExpression as? JSReferenceExpression ?: return true
        val methodName = methodExpr.referenceName ?: return true

        val i18nFunctions = I18nFunctionResolver.getFunctions(element.project)
        if (!i18nFunctions.contains(methodName)) return true

        val partialKey = literal.stringValue ?: return true
        if (partialKey.isBlank()) return true

        val fullKey = I18nNamespaceResolver.getFullKey(callExpression, partialKey)

        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        var entries = cacheService.getAllTranslations(fullKey)
        if (entries.isEmpty() && fullKey != partialKey) {
            entries = cacheService.getAllTranslations(partialKey)
        }

        if (entries.isEmpty()) return true

        val seen = mutableSetOf<String>()
        val psiManager = PsiManager.getInstance(project)
        for (entry in entries.values) {
            val uniqueKey = "${entry.file.path}:${entry.offset}"
            if (!seen.add(uniqueKey)) continue

            val psiElement = psiManager.findFile(entry.file)?.findElementAt(entry.offset) ?: continue
            val target = I18nNavigationTarget(psiElement, entry, project.basePath)
            if (!consumer.process(target)) return false
        }

        return true
    }
}
