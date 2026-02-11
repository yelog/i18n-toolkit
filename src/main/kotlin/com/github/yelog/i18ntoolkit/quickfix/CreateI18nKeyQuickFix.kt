package com.github.yelog.i18ntoolkit.quickfix

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nKeyCreationSupport
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Quick fix to create a missing i18n key in translation files.
 */
class CreateI18nKeyQuickFix(
    private val key: String,
    private val displayKey: String = key
) : IntentionAction, PriorityAction {

    override fun getText(): String = "Create i18n key '$displayKey'"

    override fun getFamilyName(): String = "I18n Toolkit"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        ApplicationManager.getApplication().invokeLater {
            createKeyInTranslationFiles(project, key)
        }
    }

    override fun startInWriteAction(): Boolean = false

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

    private fun createKeyInTranslationFiles(project: Project, fullKey: String) {
        val cacheService = I18nCacheService.getInstance(project)
        val targetFiles = I18nKeyCreationSupport.findTargetFiles(cacheService, fullKey)

        if (targetFiles.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("I18n Toolkit")
                .createNotification(
                    "Cannot find translation files for key '$fullKey'. Please create the key manually.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }

        val displayLocale = I18nSettingsState.getInstance(project).getDisplayLocaleOrNull()
        var displayLocaleResult: Pair<VirtualFile, Int>? = null
        var firstCreatedResult: Pair<VirtualFile, Int>? = null

        for (translationFile in targetFiles) {
            val offset = I18nKeyCreationSupport.createKeyInTranslationFile(
                project = project,
                translationFile = translationFile,
                fullKey = fullKey,
                initialValue = ""
            )

            if (offset != null) {
                if (firstCreatedResult == null) {
                    firstCreatedResult = translationFile.file to offset
                }
                if (displayLocale != null && translationFile.locale == displayLocale && displayLocaleResult == null) {
                    displayLocaleResult = translationFile.file to offset
                }
            }
        }

        // Navigate to display language file, or fall back to first created
        val navigateTo = displayLocaleResult ?: firstCreatedResult
        navigateTo?.let { (file, offset) ->
            ApplicationManager.getApplication().invokeLater {
                val descriptor = OpenFileDescriptor(project, file, offset)
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                // Select the empty value between quotes so user can type immediately
                if (editor != null) {
                    val text = editor.document.text
                    if (offset < text.length) {
                        val ch = text[offset]
                        if (ch == '"' || ch == '\'') {
                            editor.caretModel.moveToOffset(offset + 1)
                        } else {
                            editor.caretModel.moveToOffset(offset)
                        }
                    }
                }
            }
        }

        cacheService.refresh()
    }
}
