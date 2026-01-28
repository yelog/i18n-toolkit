package com.github.yelog.i18ntoolkit.popup

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nTranslationWriter
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

object I18nTranslationEditPopup {

    fun show(
        project: Project,
        editor: Editor,
        key: String,
        translations: Map<String, TranslationEntry>,
        displayLocale: String?,
        allLocales: List<String>
    ) {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Header
        val header = JBLabel(key)
        header.font = header.font.deriveFont(Font.BOLD)
        header.border = JBUI.Borders.emptyBottom(8)
        panel.add(header, BorderLayout.NORTH)

        // Sort locales: display locale first, rest sorted
        val sortedLocales = allLocales.sortedWith(compareBy<String> { it != displayLocale }.thenBy { it })

        // Fields panel
        val fieldsPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0)
        }

        val textFields = mutableListOf<JBTextField>()
        val cacheService = I18nCacheService.getInstance(project)

        for ((index, locale) in sortedLocales.withIndex()) {
            val entry = translations[locale]

            // Label
            gbc.gridx = 0
            gbc.gridy = index
            gbc.weightx = 0.0
            gbc.insets = JBUI.insets(2, 0, 2, 8)
            val label = JBLabel("$locale:")
            label.font = label.font.deriveFont(Font.BOLD)
            fieldsPanel.add(label, gbc)

            // Text field
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.insets = JBUI.insets(2, 0)
            val field = JBTextField(entry?.value ?: "")
            field.columns = 40
            textFields.add(field)
            fieldsPanel.add(field, gbc)

            // Debounced write on edit
            if (entry != null) {
                val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
                field.document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = scheduleUpdate()
                    override fun removeUpdate(e: DocumentEvent) = scheduleUpdate()
                    override fun changedUpdate(e: DocumentEvent) = scheduleUpdate()

                    private fun scheduleUpdate() {
                        alarm.cancelAllRequests()
                        alarm.addRequest({
                            val newValue = field.text
                            if (newValue != entry.value) {
                                I18nTranslationWriter.updateTranslationValue(project, entry, newValue)
                                cacheService.refresh()
                            }
                        }, 300)
                    }
                })
            }

            // Select all on focus
            field.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    SwingUtilities.invokeLater { field.selectAll() }
                }
            })
        }

        // Custom focus traversal: Tab cycles through fields, wrapping
        if (textFields.size > 1) {
            fieldsPanel.isFocusCycleRoot = true
            fieldsPanel.focusTraversalPolicy = object : FocusTraversalPolicy() {
                override fun getComponentAfter(aContainer: Container?, aComponent: Component?): Component {
                    val idx = textFields.indexOf(aComponent)
                    return if (idx >= 0) textFields[(idx + 1) % textFields.size] else textFields[0]
                }

                override fun getComponentBefore(aContainer: Container?, aComponent: Component?): Component {
                    val idx = textFields.indexOf(aComponent)
                    return if (idx >= 0) textFields[(idx - 1 + textFields.size) % textFields.size] else textFields.last()
                }

                override fun getFirstComponent(aContainer: Container?) = textFields.first()
                override fun getLastComponent(aContainer: Container?) = textFields.last()
                override fun getDefaultComponent(aContainer: Container?) = textFields.first()
            }
        }

        panel.add(fieldsPanel, BorderLayout.CENTER)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textFields.firstOrNull())
            .setTitle("Edit Translations")
            .setFocusable(true)
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
            .createPopup()

        popup.showInBestPositionFor(editor)
    }
}
