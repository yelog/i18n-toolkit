package com.github.yelog.i18nhelper.quickfix

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Quick fix to create a missing i18n key in translation files.
 */
class CreateI18nKeyQuickFix(
    private val key: String,
    private val displayKey: String = key
) : IntentionAction, PriorityAction {

    override fun getText(): String = "Create i18n key '$displayKey'"

    override fun getFamilyName(): String = "I18n Helper"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        // For now, just show a hint that the feature will be implemented
        // In a full implementation, this would:
        // 1. Find the appropriate translation file(s)
        // 2. Add the key with a placeholder value
        // 3. Navigate to the newly created key

        if (editor != null) {
            com.intellij.codeInsight.hint.HintManager.getInstance().showInformationHint(
                editor,
                "Feature coming soon: This will create key '$key' in translation files"
            )
        }
    }

    override fun startInWriteAction(): Boolean = false

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH
}
