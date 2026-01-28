package com.github.yelog.i18ntoolkit.action

import com.github.yelog.i18ntoolkit.popup.I18nTranslationEditPopup
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nKeyExtractor
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wraps the built-in QuickJavaDoc action. When the caret is on an i18n key,
 * shows the editable translation popup instead of the standard documentation panel.
 * Otherwise delegates to the original QuickJavaDoc action.
 */
class I18nQuickDocAction(val originalAction: AnAction) : AnAction() {

    init {
        copyFrom(originalAction)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (project != null && editor != null && psiFile != null) {
            val offset = editor.caretModel.offset
            val cacheService = I18nCacheService.getInstance(project)

            // Only show edit popup when caret is inside a string literal (including quotes)
            // or inside a translation file. On `t` or other code, delegate to default behavior.
            val isInStringOrTranslationFile = ReadAction.compute<Boolean, RuntimeException> {
                val virtualFile = psiFile.virtualFile
                if (virtualFile != null && I18nDirectoryScanner.isTranslationFile(virtualFile)) {
                    true
                } else {
                    val elementAtCaret = psiFile.findElementAt(offset)
                    val literal = PsiTreeUtil.getParentOfType(elementAtCaret, JSLiteralExpression::class.java, false)
                    literal != null && literal.isStringLiteral && literal.textRange.containsOffset(offset)
                }
            }

            if (!isInStringOrTranslationFile) {
                originalAction.actionPerformed(e)
                return
            }

            val candidate = ReadAction.compute<com.github.yelog.i18ntoolkit.util.I18nKeyCandidate?, RuntimeException> {
                I18nKeyExtractor.findKeyAtOffset(psiFile, offset, cacheService)
            }

            if (candidate != null) {
                val fullKey = candidate.fullKey
                val partialKey = candidate.partialKey

                var translations = cacheService.getAllTranslations(fullKey)
                val displayKey = if (translations.isNotEmpty()) {
                    fullKey
                } else if (fullKey != partialKey) {
                    translations = cacheService.getAllTranslations(partialKey)
                    partialKey
                } else {
                    fullKey
                }

                if (translations.isNotEmpty()) {
                    val settings = I18nSettingsState.getInstance(project)
                    val displayLocale = settings.getDisplayLocaleOrNull()
                    val allLocales = cacheService.getAvailableLocales()
                    I18nTranslationEditPopup.show(project, editor, displayKey, translations, displayLocale, allLocales)
                    return
                }
            }
        }

        // Not an i18n key — delegate to original Quick Documentation
        originalAction.actionPerformed(e)
    }

    override fun update(e: AnActionEvent) {
        originalAction.update(e)
    }

    override fun getActionUpdateThread() = originalAction.actionUpdateThread
}
