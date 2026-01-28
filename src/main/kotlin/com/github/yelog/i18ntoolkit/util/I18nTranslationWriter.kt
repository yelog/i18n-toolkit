package com.github.yelog.i18ntoolkit.util

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.model.TranslationFileType
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

object I18nTranslationWriter {

    fun updateTranslationValue(project: Project, entry: TranslationEntry, newValue: String) {
        val fileType = TranslationFileType.fromExtension(entry.file.extension ?: "") ?: return

        when (fileType) {
            TranslationFileType.JSON -> updateJsonValue(project, entry, newValue)
            TranslationFileType.JAVASCRIPT, TranslationFileType.TYPESCRIPT -> updateJsValue(project, entry, newValue)
            TranslationFileType.PROPERTIES -> updatePropertiesValue(project, entry, newValue)
            else -> {}
        }
    }

    private fun updateJsonValue(project: Project, entry: TranslationEntry, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Update i18n Translation", null, Runnable {
            val psiFile = PsiManager.getInstance(project).findFile(entry.file) ?: return@Runnable
            val element = psiFile.findElementAt(entry.offset) ?: return@Runnable

            // Walk up to find the JsonProperty
            var current = element
            while (current != null && current !is JsonProperty) {
                current = current.parent
            }
            val property = current as? JsonProperty ?: return@Runnable
            val valueElement = property.value as? JsonStringLiteral ?: return@Runnable

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@Runnable
            val range = valueElement.textRange
            // Replace the entire string literal including quotes
            val escaped = newValue.replace("\\", "\\\\").replace("\"", "\\\"")
            document.replaceString(range.startOffset, range.endOffset, "\"$escaped\"")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        })
    }

    private fun updateJsValue(project: Project, entry: TranslationEntry, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Update i18n Translation", null, Runnable {
            val psiFile = PsiManager.getInstance(project).findFile(entry.file) ?: return@Runnable
            val element = psiFile.findElementAt(entry.offset) ?: return@Runnable

            var current = element
            while (current != null && current !is JSProperty) {
                current = current.parent
            }
            val property = current as? JSProperty ?: return@Runnable
            val valueElement = property.value as? JSLiteralExpression ?: return@Runnable

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@Runnable
            val range = valueElement.textRange
            val text = valueElement.text
            // Detect quote style
            val quote = if (text.startsWith("'")) "'" else "\""
            val escaped = if (quote == "'") {
                newValue.replace("\\", "\\\\").replace("'", "\\'")
            } else {
                newValue.replace("\\", "\\\\").replace("\"", "\\\"")
            }
            document.replaceString(range.startOffset, range.endOffset, "$quote$escaped$quote")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        })
    }

    private fun updatePropertiesValue(project: Project, entry: TranslationEntry, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Update i18n Translation", null, Runnable {
            val document = FileDocumentManager.getInstance().getDocument(entry.file) ?: return@Runnable
            val lineNumber = document.getLineNumber(entry.offset)
            val lineStart = document.getLineStartOffset(lineNumber)
            val lineEnd = document.getLineEndOffset(lineNumber)
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

            val eqIndex = lineText.indexOf('=')
            if (eqIndex < 0) return@Runnable

            val valueStart = lineStart + eqIndex + 1
            document.replaceString(valueStart, lineEnd, newValue)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        })
    }
}
