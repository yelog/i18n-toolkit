package com.github.yelog.i18ntoolkit.action

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.github.yelog.i18ntoolkit.searcheverywhere.I18nSearchEverywhereContributor

class I18nTranslationsPopupAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get initial query: use selected text if available, otherwise empty string
        val editor = e.getData(CommonDataKeys.EDITOR)
        val initialQuery = editor?.selectionModel?.selectedText?.trim() ?: ""

        val manager = SearchEverywhereManager.getInstance(project)
        if (manager.isShown) {
            manager.setSelectedTabID(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID)
            val ui = manager.currentlyShownUI
            if (ui != null && initialQuery.isNotEmpty()) {
                ui.searchField.text = initialQuery
            }
            return
        }

        manager.show(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID, initialQuery, e)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
