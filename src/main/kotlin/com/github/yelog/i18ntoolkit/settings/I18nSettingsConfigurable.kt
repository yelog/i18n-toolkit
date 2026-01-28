package com.github.yelog.i18ntoolkit.settings

import javax.swing.JComponent
import javax.swing.JLabel
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nLocaleUtils
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher

class I18nSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = I18nSettingsState.getInstance(project)
    private val cacheService = I18nCacheService.getInstance(project)
    private val localeModel = CollectionComboBoxModel<String>()

    private var displayLocale: String = settings.state.displayLocale
    private var displayMode: I18nDisplayMode = settings.state.displayMode
    private var frameworkSetting: I18nFrameworkSetting = settings.state.frameworkSetting
    private var customI18nFunctions: String = settings.state.customI18nFunctions

    private var panel: com.intellij.openapi.ui.DialogPanel? = null
    private var detectedFrameworkLabel: JLabel? = null

    override fun getDisplayName(): String = "I18n Toolkit"

    override fun createComponent(): JComponent {
        refreshLocales()
        val detectedLabel = JLabel(getDetectedFrameworkText()).apply { foreground = JBColor.GRAY }
        detectedFrameworkLabel = detectedLabel

        val panel = panel {
            group("General") {
                row("Preview language") {
                    comboBox(localeModel)
                        .bindItem({ displayLocale }, { displayLocale = it ?: "" })
                        .align(AlignX.FILL)
                }
                buttonsGroup("Display mode") {
                    row {
                        radioButton("Inline (Append to key)", I18nDisplayMode.INLINE)
                    }
                    row {
                        radioButton("Replace (Hide key)", I18nDisplayMode.TRANSLATION_ONLY)
                    }
                }.bind({ displayMode }, { displayMode = it })
            }

            group("Framework") {
                var frameworkCombo: com.intellij.openapi.ui.ComboBox<I18nFrameworkSetting>? = null
                row("Framework type") {
                    frameworkCombo = comboBox(I18nFrameworkSetting.entries, SimpleListCellRenderer.create("") { it.displayName })
                        .bindItem({ frameworkSetting }, { frameworkSetting = it ?: I18nFrameworkSetting.AUTO })
                        .align(AlignX.FILL)
                        .component
                }
                row { cell(detectedLabel) }.topGap(TopGap.SMALL)
                frameworkCombo?.let { combo ->
                    updateDetectedFrameworkLabelVisibility(combo.selectedItem as? I18nFrameworkSetting)
                    combo.addActionListener {
                        updateDetectedFrameworkLabelVisibility(combo.selectedItem as? I18nFrameworkSetting)
                    }
                }
            }

            group("Customization") {
                row("Function names") {
                    textField()
                        .bindText({ customI18nFunctions }, { customI18nFunctions = it })
                        .align(AlignX.FILL)
                        .comment("Use comma to separate multiple function names, e.g. t, \$t, i18n.t")
                }
            }

            group("Shortcuts") {
                row {
                    label("Translation popup:")
                    cell(createShortcutTag(getShortcutText(I18nUiRefresher.TRANSLATIONS_POPUP_ACTION_ID)))
                        .gap(RightGap.SMALL)
                    link("Change...") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Keymap")
                    }
                }
            }
        }

        this.panel = panel
        return panel
    }

    override fun isModified(): Boolean {
        return panel?.isModified() ?: false
    }

    override fun apply() {
        panel?.apply()
        settings.state.displayLocale = displayLocale
        settings.state.displayMode = displayMode
        settings.state.frameworkSetting = frameworkSetting
        settings.state.customI18nFunctions = customI18nFunctions
        updateDetectedFrameworkLabel()
        I18nUiRefresher.refresh(project)
    }

    override fun reset() {
        displayLocale = settings.state.displayLocale
        displayMode = settings.state.displayMode
        frameworkSetting = settings.state.frameworkSetting
        customI18nFunctions = settings.state.customI18nFunctions
        panel?.reset()
        updateDetectedFrameworkLabel()
    }

    override fun disposeUIResources() {
        panel = null
        detectedFrameworkLabel = null
    }

    private fun refreshLocales() {
        val locales = cacheService.getAvailableLocales().sorted()
        localeModel.replaceAll(locales)
        if (displayLocale !in locales && locales.isNotEmpty()) {
            val normalized = I18nLocaleUtils.normalizeLocale(displayLocale)
            val matched = locales.firstOrNull { I18nLocaleUtils.normalizeLocale(it) == normalized }
            displayLocale = matched ?: locales.first()
        }
    }

    private fun getDetectedFrameworkText(): String {
        val framework = cacheService.getFramework()
        return "Currently detected: ${framework.displayName}"
    }

    private fun updateDetectedFrameworkLabel() {
        detectedFrameworkLabel?.text = getDetectedFrameworkText()
        updateDetectedFrameworkLabelVisibility(frameworkSetting)
    }

    private fun updateDetectedFrameworkLabelVisibility(frameworkSetting: I18nFrameworkSetting?) {
        detectedFrameworkLabel?.isVisible = frameworkSetting == I18nFrameworkSetting.AUTO
    }

    private fun getShortcutText(actionId: String): String {
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcut = keymap.getShortcuts(actionId)
            .filterIsInstance<com.intellij.openapi.actionSystem.KeyboardShortcut>()
            .firstOrNull()
        return if (shortcut != null) KeymapUtil.getShortcutText(shortcut) else "Not set"
    }

    private fun createShortcutTag(text: String): JBLabel {
        return JBLabel(text).apply {
            isOpaque = true
            border = JBUI.Borders.empty(2, 6)
            background = JBColor.namedColor(
                "Badge.background",
                JBColor(0xE0E0E0, 0x3C3F41)
            )
            foreground = JBColor.namedColor(
                "Badge.foreground",
                JBColor(0x222222, 0xEEEEEE)
            )
        }
    }

}
