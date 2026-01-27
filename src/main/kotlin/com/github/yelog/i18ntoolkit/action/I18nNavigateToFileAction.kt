package com.github.yelog.i18ntoolkit.action

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nKeyCandidate
import com.github.yelog.i18ntoolkit.util.I18nKeyExtractor

/**
 * Action to navigate from an i18n key usage to its definition in the translation file.
 * Uses the display locale from settings to determine which translation file to navigate to.
 */
class I18nNavigateToFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val keyCandidate = I18nKeyExtractor.findKeyAtOffset(psiFile, editor.caretModel.offset, cacheService)
        val key = resolveDisplayKey(keyCandidate, cacheService)
        if (key.isNullOrBlank()) {
            showHint(editor, "No i18n key found at caret")
            return
        }

        // Get display locale from settings
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Get the translation entry for the display locale
        val translations = cacheService.getAllTranslations(key)
        if (translations.isEmpty()) {
            showHint(editor, "No translations found for key: $key")
            return
        }

        // Find the entry for display locale, or fall back to first available
        val entry = if (displayLocale != null) {
            translations[displayLocale] ?: translations.values.firstOrNull()
        } else {
            // Prefer zh_CN, zh, en, then first available
            translations["zh_CN"] ?: translations["zh"] ?: translations["en"] ?: translations.values.firstOrNull()
        }

        if (entry == null) {
            showHint(editor, "No translation entry found for key: $key")
            return
        }

        // Navigate to the translation file
        val descriptor = OpenFileDescriptor(project, entry.file, entry.offset)
        if (descriptor.canNavigate()) {
            descriptor.navigate(true)
        } else {
            showHint(editor, "Cannot navigate to translation file")
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }

    private fun resolveDisplayKey(
        keyCandidate: I18nKeyCandidate?,
        cacheService: I18nCacheService
    ): String? {
        if (keyCandidate == null) return null
        if (cacheService.getAllTranslations(keyCandidate.fullKey).isNotEmpty()) {
            return keyCandidate.fullKey
        }
        if (keyCandidate.fullKey != keyCandidate.partialKey &&
            cacheService.getAllTranslations(keyCandidate.partialKey).isNotEmpty()
        ) {
            return keyCandidate.partialKey
        }
        return keyCandidate.fullKey
    }

    private fun showHint(editor: Editor, message: String) {
        HintManager.getInstance().showInformationHint(editor, message)
    }
}
