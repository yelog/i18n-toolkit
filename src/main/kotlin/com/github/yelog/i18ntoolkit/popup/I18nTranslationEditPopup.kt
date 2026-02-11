package com.github.yelog.i18ntoolkit.popup

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nTranslationConsistencySupport
import com.github.yelog.i18ntoolkit.util.I18nTranslationWriter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.FocusTraversalPolicy
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
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

        val header = JBLabel(key)
        header.font = header.font.deriveFont(Font.BOLD)
        header.border = JBUI.Borders.emptyBottom(8)
        panel.add(header, BorderLayout.NORTH)

        val sortedLocales = allLocales.sortedWith(compareBy<String> { it != displayLocale }.thenBy { it })

        val fieldsPanel = JPanel(java.awt.GridBagLayout())
        val gbc = java.awt.GridBagConstraints().apply {
            fill = java.awt.GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0)
        }

        val textFields = mutableListOf<JBTextField>()
        val cacheService = I18nCacheService.getInstance(project)

        var popupRef: JBPopup? = null

        for ((index, locale) in sortedLocales.withIndex()) {
            var entry = translations[locale]

            gbc.gridx = 0
            gbc.gridy = index
            gbc.weightx = 0.0
            gbc.insets = JBUI.insets(2, 0, 2, 8)
            val label = JBLabel("$locale:")
            label.font = label.font.deriveFont(Font.BOLD)
            fieldsPanel.add(label, gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.insets = JBUI.insets(2, 0)
            val field = JBTextField(entry?.value ?: "")
            field.columns = 40
            textFields.add(field)
            fieldsPanel.add(field, gbc)

            val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = scheduleUpdate()
                override fun removeUpdate(e: DocumentEvent) = scheduleUpdate()
                override fun changedUpdate(e: DocumentEvent) = scheduleUpdate()

                private fun scheduleUpdate() {
                    alarm.cancelAllRequests()
                    alarm.addRequest({
                        val newValue = field.text

                        if (entry == null) {
                            val created = I18nTranslationConsistencySupport.createMissingTranslation(
                                project = project,
                                key = key,
                                locale = locale,
                                initialValue = newValue
                            )
                            if (created != null) {
                                entry = created
                            }
                            return@addRequest
                        }

                        val current = entry ?: return@addRequest
                        if (newValue != current.value) {
                            I18nTranslationWriter.updateTranslationValue(project, current, newValue)
                            cacheService.refresh()
                            entry = current.copy(value = newValue)
                        }
                    }, 300)
                }
            })

            field.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    SwingUtilities.invokeLater { field.selectAll() }
                }
            })
        }

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

        val missingTargets = I18nTranslationConsistencySupport.collectMissingTargets(cacheService, key, sortedLocales)
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 8))
        val missingLabel = JBLabel(
            if (missingTargets.isEmpty()) {
                "All locales are complete"
            } else {
                "Missing translations: ${missingTargets.joinToString { it.locale }}"
            }
        )
        actionsPanel.add(missingLabel)

        val fillButton = JButton("Fill Missing")
        fillButton.isEnabled = missingTargets.isNotEmpty()
        fillButton.addActionListener {
            val createdCount = I18nTranslationConsistencySupport.fillMissingTranslations(
                project = project,
                key = key,
                allLocales = sortedLocales
            )
            if (createdCount <= 0) return@addActionListener

            val updatedTranslations = cacheService.getAllTranslations(key)
            val updatedLocales = cacheService.getAvailableLocales()
            popupRef?.cancel()
            ApplicationManager.getApplication().invokeLater {
                show(project, editor, key, updatedTranslations, displayLocale, updatedLocales)
            }
        }
        actionsPanel.add(fillButton)
        panel.add(actionsPanel, BorderLayout.SOUTH)

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

        popupRef = popup
        popup.showInBestPositionFor(editor)
    }
}
