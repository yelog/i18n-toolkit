package com.github.yelog.i18ntoolkit.util

import com.github.yelog.i18ntoolkit.folding.I18nFoldingBuilder
import com.github.yelog.i18ntoolkit.hint.I18nInlayHintsProvider
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.FileContentUtil

object I18nUiRefresher {
    const val SWITCH_LOCALE_ACTION_ID = "I18nToolkit.SwitchLocale"
    const val TRANSLATIONS_POPUP_ACTION_ID = "I18nToolkit.ShowTranslationsPopup"
    const val COPY_KEY_ACTION_ID = "I18nToolkit.CopyKey"
    const val NAVIGATE_TO_FILE_ACTION_ID = "I18nToolkit.NavigateToFile"
    const val STATUS_BAR_WIDGET_ID = "I18nDisplayLanguage"

    fun refresh(project: Project) {
        // Clear all caches
        I18nInlayHintsProvider.clearCache()
        I18nFoldingBuilder.clearCache()

        ApplicationManager.getApplication().invokeLater {
            // Get all open files
            val fileEditorManager = FileEditorManager.getInstance(project)
            val openFiles = fileEditorManager.openFiles.toList()

            if (openFiles.isNotEmpty()) {
                // Reparse files to force rebuild of inlay hints and folding regions
                FileContentUtil.reparseFiles(project, openFiles, true)
            }

            // Also restart daemon for good measure
            DaemonCodeAnalyzer.getInstance(project).restart()

            // Update status bar widget
            updateStatusBarWidget(project)
        }
    }

    private fun updateStatusBarWidget(project: Project) {
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        statusBar?.updateWidget(STATUS_BAR_WIDGET_ID)
    }
}
