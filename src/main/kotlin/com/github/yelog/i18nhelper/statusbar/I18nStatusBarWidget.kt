package com.github.yelog.i18nhelper.statusbar

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.github.yelog.i18nhelper.util.I18nUiRefresher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Status bar widget for displaying and switching i18n display language
 */
class I18nStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "I18nDisplayLanguage"
        private const val EMPTY_LOCALE_TEXT = "No Locale"
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        thisLogger().info("I18n status bar widget installed")
    }

    override fun dispose() {
        // Nothing to dispose
    }

    // TextPresentation methods
    override fun getText(): String {
        return getCurrentDisplayText()
    }

    override fun getTooltipText(): String {
        return "I18n Display Language: ${getCurrentDisplayText()}\nClick to switch language"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { event ->
            thisLogger().info("I18n status bar widget clicked")
            showPopup(event.component)
        }
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    private fun getCurrentDisplayText(): String {
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()
        return displayLocale ?: EMPTY_LOCALE_TEXT
    }

    private fun showPopup(component: Component) {
        val popup = createPopup() ?: return
        popup.showUnderneathOf(component)
    }

    private fun createPopup(): ListPopup? {
        val cacheService = I18nCacheService.getInstance(project)
        val settings = I18nSettingsState.getInstance(project)

        var locales = cacheService.getAvailableLocales().sorted()
        if (locales.isEmpty()) {
            cacheService.refresh()
            locales = cacheService.getAvailableLocales().sorted()
        }

        if (locales.isEmpty()) {
            thisLogger().warn("No locales available for i18n status bar widget")
            return null
        }

        thisLogger().info("Creating popup with ${locales.size} locales: $locales")

        val currentLocale = settings.getDisplayLocaleOrNull()

        val step = object : BaseListPopupStep<String>("Select I18n Display Language", locales) {
            override fun onChosen(selectedValue: String?, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != null && selectedValue != currentLocale) {
                    ApplicationManager.getApplication().invokeLater {
                        settings.state.displayLocale = selectedValue
                        thisLogger().info("Changed display locale to: $selectedValue")
                        I18nUiRefresher.refresh(project)
                    }
                }
                return super.onChosen(selectedValue, finalChoice)
            }

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun getDefaultOptionIndex(): Int {
                val index = values.indexOf(currentLocale)
                return if (index >= 0) index else 0
            }
        }

        return JBPopupFactory.getInstance().createListPopup(step)
    }
}
