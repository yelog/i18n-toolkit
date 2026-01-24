package com.github.yelog.i18nhelper.reference

import com.github.yelog.i18nhelper.scanner.I18nDirectoryScanner
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

class I18nUsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val element = queryParameters.elementToSearch
        val project = element.project

        val key = extractKeyFromElement(element, project) ?: return

        val scope = queryParameters.effectiveSearchScope
        if (scope is GlobalSearchScope) {
            searchForKeyUsages(project, key, scope, consumer)
        }
    }

    private fun extractKeyFromElement(element: PsiElement, project: Project): String? {
        return when (element) {
            is JsonProperty -> extractKeyFromJsonProperty(element, project)
            is JSProperty -> extractKeyFromJsProperty(element, project)
            else -> null
        }
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
        scope: GlobalSearchScope,
        consumer: Processor<in PsiReference>
    ) {
        val baseDir = project.guessProjectDir() ?: return
        val psiManager = PsiManager.getInstance(project)
        val sourceExtensions = listOf("js", "jsx", "ts", "tsx", "vue", "mjs", "cjs")

        VfsUtil.iterateChildrenRecursively(baseDir, { file ->
            !file.name.startsWith(".") &&
            file.name != "node_modules" &&
            file.name != "dist" &&
            file.name != "build" &&
            !file.path.contains("/node_modules/")
        }) { file ->
            if (!file.isDirectory && sourceExtensions.contains(file.extension?.lowercase())) {
                val psiFile = psiManager.findFile(file)
                if (psiFile != null && scope.contains(file)) {
                    findKeyUsagesInFile(psiFile, key, consumer)
                }
            }
            true
        }
    }

    private fun findKeyUsagesInFile(
        psiFile: com.intellij.psi.PsiFile,
        key: String,
        consumer: Processor<in PsiReference>
    ) {
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is JSCallExpression) {
                    checkCallExpression(element, key, consumer)
                }
                super.visitElement(element)
            }
        })
    }

    private fun checkCallExpression(
        callExpr: JSCallExpression,
        key: String,
        consumer: Processor<in PsiReference>
    ) {
        val methodExpr = callExpr.methodExpression as? JSReferenceExpression ?: return
        val methodName = methodExpr.referenceName ?: return

        if (!i18nFunctions.contains(methodName)) return

        val args = callExpr.arguments
        if (args.isEmpty()) return

        val firstArg = args[0] as? JSLiteralExpression ?: return
        val argValue = firstArg.stringValue ?: return

        if (argValue == key) {
            firstArg.references.forEach { ref ->
                consumer.process(ref)
            }
            if (firstArg.references.isEmpty()) {
                consumer.process(I18nKeyReference(firstArg, firstArg.textRange, key))
            }
        }
    }
}
