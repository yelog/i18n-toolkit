package com.github.yelog.i18nhelper.action

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.github.yelog.i18nhelper.searcheverywhere.I18nSearchEverywhereContributor

class I18nTranslationsPopupAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // Get initial query: use selected text if available, otherwise empty string
        val initialQuery = editor.selectionModel.selectedText?.trim() ?: ""

        val manager = SearchEverywhereManager.getInstance(project)
        if (manager.isShown()) {
            manager.setSelectedTabID(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID)
            val ui = manager.getCurrentlyShownUI()
            if (ui != null) {
                ui.searchField.text = initialQuery
            }
            return
        }

        manager.show(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID, initialQuery, e)
    }
}
