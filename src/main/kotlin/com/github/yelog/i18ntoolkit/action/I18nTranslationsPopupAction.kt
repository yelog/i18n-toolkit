package com.github.yelog.i18ntoolkit.action

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.github.yelog.i18ntoolkit.searcheverywhere.I18nSearchEverywhereContributor
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState

class I18nTranslationsPopupAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get initial query: use selected text if available, otherwise use last search query
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText?.trim()

        val settings = I18nSettingsState.getInstance(project)
        val initialQuery = if (!selectedText.isNullOrEmpty()) {
            selectedText
        } else {
            settings.state.lastSearchQuery
        }

        val manager = SearchEverywhereManager.getInstance(project)

        // Always show the Search Everywhere dialog with our tab selected and initial query
        // This approach avoids using the deprecated getCurrentlyShownUI() method
        manager.show(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID, initialQuery, e)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
