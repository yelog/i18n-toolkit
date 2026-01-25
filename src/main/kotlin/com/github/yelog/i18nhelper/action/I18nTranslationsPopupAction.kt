package com.github.yelog.i18nhelper.action

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.github.yelog.i18nhelper.searcheverywhere.I18nSearchEverywhereContributor
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nKeyCandidate
import com.github.yelog.i18nhelper.util.I18nKeyExtractor

class I18nTranslationsPopupAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val keyCandidate = I18nKeyExtractor.findKeyAtOffset(psiFile, editor.caretModel.offset, cacheService)
        val initialQuery = buildInitialQuery(keyCandidate, cacheService)

        val manager = SearchEverywhereManager.getInstance(project)
        if (manager.isShown()) {
            manager.setSelectedTabID(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID)
            val ui = manager.getCurrentlyShownUI()
            if (ui != null && initialQuery.isNotEmpty()) {
                ui.searchField.text = initialQuery
            }
            return
        }

        manager.show(I18nSearchEverywhereContributor.SEARCH_PROVIDER_ID, initialQuery, e)
    }

    private fun buildInitialQuery(keyCandidate: I18nKeyCandidate?, cacheService: I18nCacheService): String {
        if (keyCandidate == null) return ""
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
}
