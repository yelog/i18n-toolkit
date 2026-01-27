package com.github.yelog.i18ntoolkit.util

import com.intellij.json.psi.JsonProperty
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService

private val I18N_FUNCTIONS = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

data class I18nKeyCandidate(
    val fullKey: String,
    val partialKey: String
)

object I18nKeyExtractor {
    fun findKeyAtOffset(
        psiFile: PsiFile,
        offset: Int,
        cacheService: I18nCacheService
    ): I18nKeyCandidate? {
        val translationFileKey = findTranslationFileKey(psiFile, offset, cacheService)
        if (translationFileKey != null) {
            return I18nKeyCandidate(translationFileKey, translationFileKey)
        }

        return findI18nKeyAtOffset(psiFile, offset)
    }

    private fun findTranslationFileKey(
        psiFile: PsiFile,
        offset: Int,
        cacheService: I18nCacheService
    ): String? {
        val virtualFile = psiFile.virtualFile ?: return null
        if (!I18nDirectoryScanner.isTranslationFile(virtualFile)) return null

        val element = psiFile.findElementAt(offset)
        val property = findPropertyElement(element) ?: return null
        val translationFile = cacheService.getTranslationFile(virtualFile) ?: return null

        return buildFullKey(property, translationFile.keyPrefix)
    }

    private fun findPropertyElement(element: PsiElement?): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is JsonProperty || current is JSProperty) {
                return current
            }
            current = current.parent
        }
        return null
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

    private fun findI18nKeyAtOffset(psiFile: PsiElement, offset: Int): I18nKeyCandidate? {
        var element = psiFile.findElementAt(offset)

        while (element != null) {
            if (element is JSCallExpression) {
                return extractI18nKeys(element)
            }
            if (element is JSLiteralExpression) {
                val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
                if (call != null) {
                    return extractI18nKeys(call)
                }
            }
            element = element.parent
        }

        val searchRange = maxOf(0, offset - 50)..minOf(psiFile.textLength, offset + 50)
        val calls = PsiTreeUtil.findChildrenOfType(psiFile, JSCallExpression::class.java)
        for (call in calls) {
            if (call.textRange.startOffset in searchRange || call.textRange.endOffset in searchRange) {
                val keys = extractI18nKeys(call)
                if (keys != null && call.textRange.contains(offset)) {
                    return keys
                }
            }
        }

        return null
    }

    private fun extractI18nKeys(call: JSCallExpression): I18nKeyCandidate? {
        val methodExpr = call.methodExpression as? JSReferenceExpression ?: return null
        val methodName = methodExpr.referenceName ?: return null

        if (!I18N_FUNCTIONS.contains(methodName)) return null

        val args = call.arguments
        if (args.isEmpty()) return null

        val firstArg = args[0] as? JSLiteralExpression ?: return null
        val partialKey = firstArg.stringValue ?: return null
        val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)

        return I18nKeyCandidate(fullKey, partialKey)
    }
}
