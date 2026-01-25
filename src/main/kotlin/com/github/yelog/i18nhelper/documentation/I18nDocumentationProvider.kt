package com.github.yelog.i18nhelper.documentation

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nNamespaceResolver
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Provides Quick Documentation for i18n keys showing all translations
 */
class I18nDocumentationProvider : AbstractDocumentationProvider() {

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        val (fullKey, partialKey) = extractI18nKeys(target) ?: return null

        val cacheService = I18nCacheService.getInstance(target.project)
        // Try full key first, then fallback to partial key
        var translations = cacheService.getAllTranslations(fullKey)
        val displayKey = if (translations.isNotEmpty()) {
            fullKey
        } else if (fullKey != partialKey) {
            translations = cacheService.getAllTranslations(partialKey)
            partialKey
        } else {
            fullKey
        }

        if (translations.isEmpty()) return null

        return buildDocumentation(displayKey, translations, target)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        val (fullKey, partialKey) = extractI18nKeys(target) ?: return null

        val cacheService = I18nCacheService.getInstance(target.project)
        // Try full key first, then fallback to partial key
        var translations = cacheService.getAllTranslations(fullKey)
        val displayKey = if (translations.isNotEmpty()) {
            fullKey
        } else if (fullKey != partialKey) {
            translations = cacheService.getAllTranslations(partialKey)
            partialKey
        } else {
            fullKey
        }

        if (translations.isEmpty()) return null

        // Quick navigate info - shorter format
        val sb = StringBuilder()
        sb.append("<b>i18n:</b> <code>$displayKey</code>")
        translations.entries.sortedBy { it.key }.take(3).forEach { (locale, entry) ->
            sb.append("<br/><b>$locale:</b> ${escapeHtml(truncate(entry.value, 40))}")
        }
        if (translations.size > 3) {
            sb.append("<br/><i>... and ${translations.size - 3} more</i>")
        }
        return sb.toString()
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? {
        return generateDoc(element, originalElement)
    }

    /**
     * Returns a pair of (fullKey, partialKey) where fullKey includes namespace prefix
     */
    private fun extractI18nKeys(element: PsiElement): Pair<String, String>? {
        // If element is a string literal in i18n function call
        if (element is JSLiteralExpression) {
            val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
            if (call != null && isI18nCall(call)) {
                val partialKey = element.stringValue ?: return null
                val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)
                return Pair(fullKey, partialKey)
            }
        }

        // Check if element is inside a string literal
        val literal = PsiTreeUtil.getParentOfType(element, JSLiteralExpression::class.java, false)
        if (literal != null) {
            val call = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java)
            if (call != null && isI18nCall(call)) {
                val partialKey = literal.stringValue ?: return null
                val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)
                return Pair(fullKey, partialKey)
            }
        }

        // Check if we're in an i18n call expression
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java, false)
        if (call != null && isI18nCall(call)) {
            val args = call.arguments
            if (args.isNotEmpty()) {
                val firstArg = args[0] as? JSLiteralExpression
                val partialKey = firstArg?.stringValue ?: return null
                val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)
                return Pair(fullKey, partialKey)
            }
        }

        return null
    }

    private fun isI18nCall(call: JSCallExpression): Boolean {
        val methodExpr = call.methodExpression as? JSReferenceExpression ?: return false
        val methodName = methodExpr.referenceName ?: return false
        return i18nFunctions.contains(methodName)
    }

    private fun buildDocumentation(
        key: String,
        translations: Map<String, com.github.yelog.i18nhelper.model.TranslationEntry>,
        context: PsiElement
    ): String {
        val sb = StringBuilder()

        // Definition section - the key
        sb.append(DocumentationMarkup.DEFINITION_START)
        sb.append("<icon src='AllIcons.Nodes.ResourceBundle'/>&nbsp;")
        sb.append("<b>").append(escapeHtml(key)).append("</b>")
        sb.append(DocumentationMarkup.DEFINITION_END)

        // Content section - translations list
        sb.append(DocumentationMarkup.CONTENT_START)

        translations.entries.sortedBy { it.key }.forEach { (locale, entry) ->
            val fileName = getShortFilePath(entry, context)
            val lineNumber = getLineNumber(entry, context)

            sb.append("<p style='margin: 6px 0;'>")
            sb.append("<b><code>").append(locale).append("</code></b>")
            sb.append("&nbsp;&nbsp;")
            sb.append(escapeHtml(entry.value))
            sb.append("<br/>")
            sb.append("<span style='color:gray;font-size:90%;'>")
            sb.append("&nbsp;&nbsp;&nbsp;&nbsp;")
            sb.append(escapeHtml(fileName)).append(":").append(lineNumber)
            sb.append("</span>")
            sb.append("</p>")
        }

        sb.append(DocumentationMarkup.CONTENT_END)

        return sb.toString()
    }

    private fun getShortFilePath(entry: com.github.yelog.i18nhelper.model.TranslationEntry, context: PsiElement): String {
        val filePath = entry.file.path
        val basePath = context.project.basePath ?: return entry.file.name

        val relativePath = if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length).trimStart('/')
        } else {
            filePath
        }

        val parts = relativePath.split("/")
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString("/")
        } else {
            entry.file.name
        }
    }

    private fun getLineNumber(entry: com.github.yelog.i18nhelper.model.TranslationEntry, context: PsiElement): Int {
        val psiFile = PsiManager.getInstance(context.project).findFile(entry.file) ?: return 0
        val document: Document = PsiDocumentManager.getInstance(context.project).getDocument(psiFile) ?: return 0
        return document.getLineNumber(entry.offset) + 1
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) text.take(maxLength - 3) + "..." else text
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
