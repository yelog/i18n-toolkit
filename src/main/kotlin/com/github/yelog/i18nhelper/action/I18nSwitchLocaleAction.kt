package com.github.yelog.i18nhelper.action

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.github.yelog.i18nhelper.util.I18nUiRefresher
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class I18nSwitchLocaleAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = I18nSettingsState.getInstance(project)
        val cacheService = I18nCacheService.getInstance(project)

        var locales = cacheService.getAvailableLocales().sorted()
        if (locales.isEmpty()) {
            cacheService.refresh()
            locales = cacheService.getAvailableLocales().sorted()
        }

        if (locales.isEmpty()) return

        val current = settings.state.displayLocale
        val index = locales.indexOf(current).takeIf { it >= 0 } ?: -1
        val nextLocale = locales[(index + 1) % locales.size]
        settings.state.displayLocale = nextLocale

        I18nUiRefresher.refresh(project)
    }
}
