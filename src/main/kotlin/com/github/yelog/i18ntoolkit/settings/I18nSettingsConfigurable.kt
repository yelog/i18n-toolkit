package com.github.yelog.i18ntoolkit.settings

import javax.swing.JComponent
import javax.swing.JLabel
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.service.I18nTranslationReporter
import com.github.yelog.i18ntoolkit.util.I18nLocaleUtils
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import java.io.File

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
    private var outputDirField: TextFieldWithBrowseButton? = null

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

            group("Reports") {
                row("Output directory") {
                    val browseField = TextFieldWithBrowseButton().apply {
                        // 使用相对路径作为默认值
                        text = "i18n-reports"
                        addBrowseFolderListener(
                            "Select Output Directory",
                            "Choose where to save the i18n reports (HTML and CSV)",
                            project,
                            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        )
                    }
                    outputDirField = browseField
                    cell(browseField)
                        .align(AlignX.FILL)
                        .comment("Directory where HTML and CSV reports will be saved (relative to project root)")
                }
                row {
                    button("Generate Report") {
                        val outputDir = outputDirField?.text ?: ""
                        generateReport(outputDir)
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
        outputDirField = null
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

    private fun generateReport(outputDir: String) {
        if (outputDir.isBlank()) {
            Notifications.Bus.notify(
                Notification(
                    "I18n Toolkit",
                    "Invalid Directory",
                    "Please select a valid output directory",
                    NotificationType.WARNING
                ),
                project
            )
            return
        }

        thisLogger().info("I18n Toolkit: Generating translation reports to $outputDir...")

        val config = I18nTranslationReporter.ReportConfig(
            outputDir = outputDir,
            includeMissingDetails = true,
            includeOrphanedKeys = true
        )

        val htmlReport = I18nTranslationReporter.generateHtmlReport(project, config)
        val csvReport = I18nTranslationReporter.generateCsvReport(project, config)

        if (htmlReport != null && csvReport != null) {
            val notification = Notification(
                "I18n Toolkit",
                "i18n Report Generated",
                "Reports saved to: $outputDir",
                NotificationType.INFORMATION
            )

            notification.addAction(object : com.intellij.notification.NotificationAction("Open HTML Report") {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: Notification) {
                    // Refresh VFS to ensure the file is found
                    val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(htmlReport)
                    if (virtualFile != null) {
                        FileEditorManager.getInstance(project).openFile(virtualFile, true)
                        notification.expire()
                    } else {
                        Notifications.Bus.notify(
                            Notification(
                                "I18n Toolkit",
                                "Cannot Open Report",
                                "Failed to open HTML report file",
                                NotificationType.ERROR
                            ),
                            project
                        )
                    }
                }
            })

            Notifications.Bus.notify(notification, project)
        } else {
            Notifications.Bus.notify(
                Notification(
                    "I18n Toolkit",
                    "i18n Report Failed",
                    "No translations found to report. Ensure translation files are properly configured.",
                    NotificationType.WARNING
                ),
                project
            )
        }
    }

}
