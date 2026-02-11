package com.github.yelog.i18ntoolkit.spring

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.util.PsiTreeUtil

/**
 * Triggers auto-popup completion when typing inside Java i18n key literals.
 */
class JavaI18nTypedHandler : TypedHandlerDelegate() {

    override fun checkAutoPopup(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile
    ): Result {
        if (!shouldTrigger(charTyped)) return Result.CONTINUE
        if (!file.language.id.equals("JAVA", ignoreCase = true)) return Result.CONTINUE

        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val offset = (editor.caretModel.offset - 1).coerceAtLeast(0)
        val element = file.findElementAt(offset) ?: return Result.CONTINUE
        val literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false) ?: return Result.CONTINUE
        if (literal.value !is String) return Result.CONTINUE

        if (!isI18nContext(literal)) return Result.CONTINUE

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    private fun isI18nContext(literal: PsiLiteralExpression): Boolean {
        val methodCall = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression::class.java)
        val annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java)
        return when {
            methodCall != null -> SpringMessagePatternMatcher.isSpringI18nCall(methodCall)
            annotation != null -> isValidationAnnotationMessage(literal)
            else -> false
        }
    }

    private fun isValidationAnnotationMessage(literal: PsiLiteralExpression): Boolean {
        val nameValuePair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java)
        val attrName = nameValuePair?.name ?: nameValuePair?.attributeName
        return attrName == "message"
    }

    private fun shouldTrigger(charTyped: Char): Boolean {
        return charTyped.isLetterOrDigit() || charTyped == '.' || charTyped == '_' || charTyped == '-'
    }
}
