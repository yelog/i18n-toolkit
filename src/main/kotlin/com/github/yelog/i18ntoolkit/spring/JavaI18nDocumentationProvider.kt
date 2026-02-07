package com.github.yelog.i18ntoolkit.spring

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Provides Quick Documentation for i18n keys in Java files showing all translations.
 */
class JavaI18nDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        return resolveAndBuildDoc(element, originalElement)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        val key = extractI18nKey(target) ?: return null

        val file = target.containingFile?.virtualFile ?: return null
        val cacheService = I18nCacheService.getInstance(target.project)
        val translations = cacheService.getAllTranslationsForModule(key, file)
        if (translations.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("<b>i18n:</b> <code>$key</code>")

        val allLocales = cacheService.getAvailableLocales().sorted()
        if (allLocales.isNotEmpty()) {
            allLocales.take(3).forEach { locale ->
                val entry = translations[locale]
                if (entry != null) {
                    sb.append("<br/><b>$locale:</b> ${escapeHtml(truncate(entry.value, 40))}")
                } else {
                    sb.append("<br/><b>$locale:</b> <span style='color: #CC7832;'>\u26A0 Missing</span>")
                }
            }
            if (allLocales.size > 3) {
                sb.append("<br/><i>... and ${allLocales.size - 3} more</i>")
            }
        } else {
            translations.entries.sortedBy { it.key }.take(3).forEach { (loc, entry) ->
                sb.append("<br/><b>$loc:</b> ${escapeHtml(truncate(entry.value, 40))}")
            }
            if (translations.size > 3) {
                sb.append("<br/><i>... and ${translations.size - 3} more</i>")
            }
        }

        return sb.toString()
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? {
        return resolveAndBuildDoc(element, originalElement)
    }

    private fun resolveAndBuildDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null
        val key = extractI18nKey(target) ?: return null

        val file = target.containingFile?.virtualFile ?: return null
        val cacheService = I18nCacheService.getInstance(target.project)
        val settings = I18nSettingsState.getInstance(target.project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        val translations = cacheService.getAllTranslationsForModule(key, file)
        if (translations.isEmpty()) return null

        return buildDocumentation(key, translations, target, displayLocale, cacheService)
    }

    private fun extractI18nKey(element: PsiElement): String? {
        // If element is a PsiLiteralExpression
        if (element is PsiLiteralExpression) {
            return SpringMessagePatternMatcher.extractKey(element)?.key
        }

        // Check if element is inside a PsiLiteralExpression
        val literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false)
        if (literal != null) {
            return SpringMessagePatternMatcher.extractKey(literal)?.key
        }

        return null
    }

    private fun buildDocumentation(
        key: String,
        translations: Map<String, TranslationEntry>,
        context: PsiElement,
        displayLocale: String?,
        cacheService: I18nCacheService
    ): String {
        val sb = StringBuilder()

        sb.append(DocumentationMarkup.DEFINITION_START)
        sb.append("<icon src='AllIcons.Nodes.ResourceBundle'/>&nbsp;")
        sb.append("<b>").append(escapeHtml(key)).append("</b>")
        sb.append(DocumentationMarkup.DEFINITION_END)

        sb.append(DocumentationMarkup.CONTENT_START)

        val allLocales = cacheService.getAvailableLocales().sorted()
        if (allLocales.isEmpty()) {
            translations.entries.sortedBy { it.key }.forEach { (locale, entry) ->
                appendTranslationEntry(sb, locale, entry, context)
            }
        } else {
            allLocales.forEach { locale ->
                val entry = translations[locale]
                if (entry != null) {
                    appendTranslationEntry(sb, locale, entry, context)
                } else {
                    appendMissingEntry(sb, locale, locale == displayLocale)
                }
            }
        }

        sb.append(DocumentationMarkup.CONTENT_END)
        return sb.toString()
    }

    private fun appendTranslationEntry(
        sb: StringBuilder,
        locale: String,
        entry: TranslationEntry,
        context: PsiElement
    ) {
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

    private fun appendMissingEntry(sb: StringBuilder, locale: String, isDisplayLocale: Boolean) {
        sb.append("<p style='margin: 6px 0;")
        if (isDisplayLocale) {
            sb.append(" padding: 6px; background-color: #3C3F41; border-left: 3px solid #CC7832;")
        }
        sb.append("'>")
        sb.append("<b><code>").append(locale).append("</code></b>")
        sb.append("&nbsp;&nbsp;")
        sb.append("<span style='color: #CC7832;'>\u26A0 Missing translation</span>")
        sb.append("</p>")
    }

    private fun getShortFilePath(entry: TranslationEntry, context: PsiElement): String {
        val filePath = entry.file.path
        val basePath = context.project.basePath ?: return entry.file.name
        val relativePath = if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length).trimStart('/')
        } else {
            filePath
        }
        val parts = relativePath.split("/")
        return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else entry.file.name
    }

    private fun getLineNumber(entry: TranslationEntry, context: PsiElement): Int {
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
