package com.github.yelog.i18nhelper.settings

import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nUiRefresher

class I18nSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = I18nSettingsState.getInstance(project)
    private val cacheService = I18nCacheService.getInstance(project)
    private val localeModel = CollectionComboBoxModel<String>()

    private var displayLocale: String = settings.state.displayLocale
    private var displayMode: I18nDisplayMode = settings.state.displayMode
    private var frameworkSetting: I18nFrameworkSetting = settings.state.frameworkSetting

    private var panel: com.intellij.openapi.ui.DialogPanel? = null
    private var shortcutField: ShortcutCaptureField? = null
    private var popupShortcutField: ShortcutCaptureField? = null
    private var detectedFrameworkLabel: JLabel? = null

    override fun getDisplayName(): String = "I18n Helper"

    override fun createComponent(): JComponent {
        refreshLocales()

        val shortcut = ShortcutCaptureField()
        shortcutField = shortcut
        shortcut.setShortcut(getCurrentKeyboardShortcut(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID))

        val popupShortcut = ShortcutCaptureField()
        popupShortcutField = popupShortcut
        popupShortcut.setShortcut(getCurrentKeyboardShortcut(I18nUiRefresher.TRANSLATIONS_POPUP_ACTION_ID))

        val detectedLabel = JLabel(getDetectedFrameworkText())
        detectedFrameworkLabel = detectedLabel

        val panel = panel {
            group("Display") {
                row("Display language") {
                    comboBox(localeModel)
                        .bindItem({ displayLocale }, { displayLocale = it ?: "" })
                        .columns(COLUMNS_LARGE)
                }
                buttonsGroup {
                    row {
                        radioButton("Show translation after key", I18nDisplayMode.INLINE)
                    }
                    row {
                        radioButton("Show translation only", I18nDisplayMode.TRANSLATION_ONLY)
                    }
                }.bind({ displayMode }, { displayMode = it })
            }

            group("Language Switch") {
                row("Shortcut") {
                    cell(shortcut)
                    button("Clear") {
                        shortcut.setShortcut(null)
                    }
                }
                row {
                    label(getShortcutText(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID))
                }
            }

            group("Translations Popup") {
                row("Shortcut") {
                    cell(popupShortcut)
                    button("Clear") {
                        popupShortcut.setShortcut(null)
                    }
                }
                row {
                    label(getShortcutText(I18nUiRefresher.TRANSLATIONS_POPUP_ACTION_ID))
                }
            }

            group("Framework") {
                row("Framework") {
                    comboBox(I18nFrameworkSetting.entries, SimpleListCellRenderer.create("") { it.displayName })
                        .bindItem({ frameworkSetting }, { frameworkSetting = it ?: I18nFrameworkSetting.AUTO })
                        .columns(COLUMNS_LARGE)
                }
                row("Detected") {
                    cell(detectedLabel)
                }
            }
        }

        this.panel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val panelModified = panel?.isModified() ?: false
        return panelModified ||
            shortcutChanged(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID, shortcutField) ||
            shortcutChanged(I18nUiRefresher.TRANSLATIONS_POPUP_ACTION_ID, popupShortcutField)
    }

    override fun apply() {
        panel?.apply()
        settings.state.displayLocale = displayLocale
        settings.state.displayMode = displayMode
        settings.state.frameworkSetting = frameworkSetting
        updateShortcut(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID, shortcutField)
        updateShortcut(I18nUiRefresher.TRANSLATIONS_POPUP_ACTION_ID, popupShortcutField)
        updateDetectedFrameworkLabel()
        I18nUiRefresher.refresh(project)
    }

    override fun reset() {
        displayLocale = settings.state.displayLocale
        displayMode = settings.state.displayMode
        frameworkSetting = settings.state.frameworkSetting
        panel?.reset()
        shortcutField?.setShortcut(getCurrentKeyboardShortcut(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID))
        popupShortcutField?.setShortcut(getCurrentKeyboardShortcut(I18nUiRefresher.TRANSLATIONS_POPUP_ACTION_ID))
        updateDetectedFrameworkLabel()
    }

    override fun disposeUIResources() {
        panel = null
        shortcutField = null
        popupShortcutField = null
        detectedFrameworkLabel = null
    }

    private fun refreshLocales() {
        val locales = cacheService.getAvailableLocales().sorted()
        localeModel.replaceAll(locales)
        if (displayLocale !in locales && locales.isNotEmpty()) {
            displayLocale = locales.first()
        }
    }

    private fun getDetectedFrameworkText(): String {
        val framework = cacheService.getFramework()
        return "Detected: ${framework.displayName}"
    }

    private fun updateDetectedFrameworkLabel() {
        detectedFrameworkLabel?.text = getDetectedFrameworkText()
    }

    private fun shortcutChanged(actionId: String, field: ShortcutCaptureField?): Boolean {
        val current = getCurrentKeyboardShortcut(actionId)
        val uiShortcut = field?.shortcut
        return field != null && current != uiShortcut
    }

    private fun getCurrentKeyboardShortcut(actionId: String): KeyboardShortcut? {
        val keymap = KeymapManager.getInstance().activeKeymap
        return keymap.getShortcuts(actionId)
            .filterIsInstance<KeyboardShortcut>()
            .firstOrNull()
    }

    private fun getShortcutText(actionId: String): String {
        val shortcut = getCurrentKeyboardShortcut(actionId) ?: return "Not set"
        return "Current: ${KeymapUtil.getShortcutText(shortcut)}"
    }

    private fun updateShortcut(actionId: String, field: ShortcutCaptureField?) {
        val keymap = KeymapManager.getInstance().activeKeymap
        keymap.removeAllActionShortcuts(actionId)
        val shortcut = field?.shortcut
        if (shortcut != null) {
            keymap.addShortcut(actionId, shortcut)
        }
    }

    private class ShortcutCaptureField : JBTextField() {
        var shortcut: KeyboardShortcut? = null
            private set

        init {
            isEditable = false
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val keyStroke = KeyStroke.getKeyStrokeForEvent(e) ?: return
                    val captured = KeyboardShortcut(keyStroke, null)
                    shortcut = captured
                    text = KeymapUtil.getShortcutText(captured)
                    e.consume()
                }
            })
        }

        fun setShortcut(shortcut: KeyboardShortcut?) {
            this.shortcut = shortcut
            text = shortcut?.let { KeymapUtil.getShortcutText(it) } ?: ""
        }
    }
}
