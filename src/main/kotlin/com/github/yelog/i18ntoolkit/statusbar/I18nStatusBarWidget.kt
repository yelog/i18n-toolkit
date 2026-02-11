package com.github.yelog.i18ntoolkit.statusbar

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nLocaleUtils
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Status bar widget for displaying and switching i18n display language
 */
class I18nStatusBarWidget(private val project: Project) : StatusBarWidget {

    companion object {
        const val ID = "I18nDisplayLanguage"
        private const val EMPTY_LOCALE_TEXT = "No Locale"
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation {
        return object : StatusBarWidget.TextPresentation {
            override fun getText(): String = getCurrentDisplayText()

            override fun getTooltipText(): String {
                return "I18n Display Language: ${getCurrentDisplayText()}\nClick to switch language"
            }

            override fun getClickConsumer(): Consumer<MouseEvent> {
                return Consumer { event ->
                    thisLogger().info("I18n status bar widget clicked")
                    showPopup(event.component)
                }
            }

            override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
        }
    }

    override fun install(statusBar: StatusBar) {
        thisLogger().info("I18n status bar widget installed")
    }

    override fun dispose() {
        // Nothing to dispose
    }

    private fun getCurrentDisplayText(): String {
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = resolveCurrentLocale(settings, null)
        return displayLocale?.let { I18nLocaleUtils.formatLocaleForDisplay(it) } ?: EMPTY_LOCALE_TEXT
    }

    private fun showPopup(component: Component) {
        val popup = createPopup() ?: return
        val dimension = popup.content.preferredSize
        // Show popup above the status bar component
        val point = Point(0, -dimension.height)
        popup.show(RelativePoint(component, point))
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

        val currentLocale = resolveCurrentLocale(settings, locales)

        // Create items list: Settings option + locales
        val settingsItem = PopupItem.SettingsItem
        val localeItems = locales.map { PopupItem.LocaleItem(it) }
        val allItems = listOf(settingsItem) + localeItems

        val step = object : BaseListPopupStep<PopupItem>("I18n Toolkit", allItems) {
            override fun onChosen(selectedValue: PopupItem?, finalChoice: Boolean): PopupStep<*>? {
                when (selectedValue) {
                    is PopupItem.SettingsItem -> {
                        ApplicationManager.getApplication().invokeLater {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, "I18n Toolkit")
                        }
                    }
                    is PopupItem.LocaleItem -> {
                        if (selectedValue.locale != currentLocale) {
                            ApplicationManager.getApplication().invokeLater {
                                settings.state.displayLocale = selectedValue.locale
                                thisLogger().info("Changed display locale to: ${selectedValue.locale}")
                                I18nUiRefresher.refresh(project)
                            }
                        }
                    }
                    null -> {}
                }
                return super.onChosen(selectedValue, finalChoice)
            }

            override fun getTextFor(value: PopupItem): String {
                return when (value) {
                    is PopupItem.SettingsItem -> "Go to Settings"
                    is PopupItem.LocaleItem -> value.locale
                }
            }

            override fun getIconFor(value: PopupItem): Icon? {
                return when (value) {
                    is PopupItem.SettingsItem -> AllIcons.General.Settings
                    is PopupItem.LocaleItem -> null
                }
            }

            override fun getSeparatorAbove(value: PopupItem): ListSeparator? {
                return when (value) {
                    is PopupItem.LocaleItem -> {
                        // Show separator before first locale item
                        if (value == localeItems.firstOrNull()) {
                            ListSeparator("Display Language")
                        } else null
                    }
                    else -> null
                }
            }

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun getDefaultOptionIndex(): Int {
                // Find the index of current locale in all items
                val currentLocaleItem = if (currentLocale == null) {
                    null
                } else {
                    val normalized = I18nLocaleUtils.normalizeLocale(currentLocale)
                    localeItems.find { I18nLocaleUtils.normalizeLocale(it.locale) == normalized }
                }
                return if (currentLocaleItem != null) {
                    val index = allItems.indexOf(currentLocaleItem)
                    if (index >= 0) index else 1
                } else {
                    1 // Default to first locale if not found
                }
            }
        }

        return JBPopupFactory.getInstance().createListPopup(step)
    }

    private fun resolveCurrentLocale(
        settings: I18nSettingsState,
        availableLocales: List<String>?
    ): String? {
        val locales = availableLocales ?: I18nCacheService.getInstance(project).getAvailableLocales().sorted()
        if (locales.isEmpty()) return null

        val currentLocale = settings.getDisplayLocaleOrNull()
        if (currentLocale != null) {
            val normalizedCurrent = I18nLocaleUtils.normalizeLocale(currentLocale)
            val matched = locales.firstOrNull { I18nLocaleUtils.normalizeLocale(it) == normalizedCurrent }
            if (matched != null) {
                if (matched != currentLocale) {
                    settings.state.displayLocale = matched
                }
                return matched
            }
        }

        val fallback = locales.first()
        settings.state.displayLocale = fallback
        return fallback
    }

    /**
     * Sealed class representing popup menu items
     */
    private sealed class PopupItem {
        object SettingsItem : PopupItem()
        data class LocaleItem(val locale: String) : PopupItem()
    }
}
