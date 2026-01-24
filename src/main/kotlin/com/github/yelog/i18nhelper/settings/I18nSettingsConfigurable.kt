package com.github.yelog.i18nhelper.settings

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nUiRefresher
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class I18nSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = I18nSettingsState.getInstance(project)
    private val cacheService = I18nCacheService.getInstance(project)
    private val localeModel = CollectionComboBoxModel<String>()

    private var displayLocale: String = settings.state.displayLocale
    private var displayMode: I18nDisplayMode = settings.state.displayMode
    private var frameworkSetting: I18nFrameworkSetting = settings.state.frameworkSetting

    private var panel: com.intellij.openapi.ui.DialogPanel? = null
    private var shortcutField: ShortcutCaptureField? = null
    private var detectedFrameworkLabel: JLabel? = null

    override fun getDisplayName(): String = "I18n Helper"

    override fun createComponent(): JComponent {
        refreshLocales()

        val shortcut = ShortcutCaptureField()
        shortcutField = shortcut
        shortcut.setShortcut(getCurrentKeyboardShortcut())

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
                    label(getShortcutText())
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
        return panelModified || shortcutChanged()
    }

    override fun apply() {
        panel?.apply()
        settings.state.displayLocale = displayLocale
        settings.state.displayMode = displayMode
        settings.state.frameworkSetting = frameworkSetting
        updateShortcut()
        updateDetectedFrameworkLabel()
        I18nUiRefresher.refresh(project)
    }

    override fun reset() {
        displayLocale = settings.state.displayLocale
        displayMode = settings.state.displayMode
        frameworkSetting = settings.state.frameworkSetting
        panel?.reset()
        shortcutField?.setShortcut(getCurrentKeyboardShortcut())
        updateDetectedFrameworkLabel()
    }

    override fun disposeUIResources() {
        panel = null
        shortcutField = null
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

    private fun shortcutChanged(): Boolean {
        val current = getCurrentKeyboardShortcut()
        val uiShortcut = shortcutField?.shortcut
        return current != uiShortcut
    }

    private fun getCurrentKeyboardShortcut(): KeyboardShortcut? {
        val keymap = KeymapManager.getInstance().activeKeymap
        return keymap.getShortcuts(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID)
            .filterIsInstance<KeyboardShortcut>()
            .firstOrNull()
    }

    private fun getShortcutText(): String {
        val shortcut = getCurrentKeyboardShortcut() ?: return "Not set"
        return "Current: ${KeymapUtil.getShortcutText(shortcut)}"
    }

    private fun updateShortcut() {
        val keymap = KeymapManager.getInstance().activeKeymap
        keymap.removeAllActionShortcuts(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID)
        val shortcut = shortcutField?.shortcut
        if (shortcut != null) {
            keymap.addShortcut(I18nUiRefresher.SWITCH_LOCALE_ACTION_ID, shortcut)
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
