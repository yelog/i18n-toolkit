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
            TranslationFileType.YAML -> updateYamlValue(project, entry, newValue)
            TranslationFileType.TOML -> updateTomlValue(project, entry, newValue)
        }
    }

    private fun updateJsonValue(project: Project, entry: TranslationEntry, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Update i18n Translation", null, Runnable {
            val psiFile = PsiManager.getInstance(project).findFile(entry.file) ?: return@Runnable
            val element = psiFile.findElementAt(entry.offset) ?: return@Runnable

            var current = element
            while (current != null && current !is JsonProperty) {
                current = current.parent
            }
            val property = current as? JsonProperty ?: return@Runnable
            val valueElement = property.value as? JsonStringLiteral ?: return@Runnable

            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@Runnable
            val range = valueElement.textRange
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
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    private fun updateYamlValue(project: Project, entry: TranslationEntry, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Update i18n Translation", null, Runnable {
            val document = FileDocumentManager.getInstance().getDocument(entry.file) ?: return@Runnable
            val content = document.text
            val updatedContent = replaceYamlValue(content, resolveLocalKey(project, entry), newValue) ?: return@Runnable

            if (updatedContent != content) {
                document.setText(updatedContent)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
        })
    }

    private fun updateTomlValue(project: Project, entry: TranslationEntry, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Update i18n Translation", null, Runnable {
            val document = FileDocumentManager.getInstance().getDocument(entry.file) ?: return@Runnable
            val content = document.text
            val updatedContent = replaceTomlValue(content, resolveLocalKey(project, entry), newValue) ?: return@Runnable

            if (updatedContent != content) {
                document.setText(updatedContent)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
        })
    }

    private fun resolveLocalKey(project: Project, entry: TranslationEntry): String {
        val basePath = project.basePath ?: return entry.key
        return try {
            val pathInfo = I18nKeyGenerator.parseFilePath(entry.file, basePath)
            if (pathInfo.keyPrefix.isNotEmpty() && entry.key.startsWith(pathInfo.keyPrefix)) {
                entry.key.removePrefix(pathInfo.keyPrefix)
            } else {
                entry.key
            }
        } catch (_: Exception) {
            entry.key
        }
    }

    private fun replaceYamlValue(content: String, localKey: String, newValue: String): String? {
        val lines = content.split("\n").toMutableList()
        val stack = mutableListOf<Pair<Int, String>>()

        for (index in lines.indices) {
            val parsed = parseYamlLine(lines[index]) ?: continue

            while (stack.isNotEmpty() && parsed.indent <= stack.last().first) {
                stack.removeAt(stack.lastIndex)
            }

            val currentPath = (stack.map { it.second } + parsed.normalizedKey).joinToString(".")
            if (currentPath == localKey) {
                lines[index] = "${" ".repeat(parsed.indent)}${parsed.rawKey}: '${escapeYamlSingleQuoted(newValue)}'"
                return lines.joinToString("\n")
            }

            val valuePart = parsed.valuePart.trim()
            if (valuePart.isEmpty() || valuePart.startsWith("#")) {
                stack.add(parsed.indent to parsed.normalizedKey)
            }
        }

        return null
    }

    private fun replaceTomlValue(content: String, localKey: String, newValue: String): String? {
        val lines = content.split("\n").toMutableList()
        var currentTable = ""

        for (index in lines.indices) {
            val line = lines[index]
            val table = parseTomlTable(line)
            if (table != null) {
                currentTable = table
                continue
            }

            val parsed = parseTomlKeyValue(line) ?: continue
            val fullKey = if (currentTable.isBlank()) {
                parsed.normalizedKey
            } else {
                "$currentTable.${parsed.normalizedKey}"
            }

            if (fullKey == localKey) {
                lines[index] = "${" ".repeat(parsed.indent)}${parsed.rawKey} = \"${escapeTomlBasicString(newValue)}\""
                return lines.joinToString("\n")
            }
        }

        return null
    }

    private fun parseYamlLine(line: String): ParsedLine? {
        if (line.isBlank()) return null
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("#")) return null

        val separatorIndex = findSeparatorOutsideQuotes(line, ':')
        if (separatorIndex <= 0) return null

        val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (separatorIndex <= indent) return null

        val rawKey = line.substring(indent, separatorIndex).trimEnd()
        if (rawKey.isBlank() || rawKey.startsWith("-")) return null

        val normalizedKey = rawKey.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")

        if (normalizedKey.isBlank()) return null

        val valuePart = line.substring(separatorIndex + 1)
        return ParsedLine(indent, rawKey, normalizedKey, valuePart)
    }

    private fun parseTomlTable(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("[[")) {
            val closing = trimmed.indexOf("]]", startIndex = 2)
            if (closing > 2) {
                return trimmed.substring(2, closing).trim()
            }
        }
        if (trimmed.startsWith("[")) {
            val closing = trimmed.indexOf(']', startIndex = 1)
            if (closing > 1) {
                return trimmed.substring(1, closing).trim()
            }
        }
        return null
    }

    private fun parseTomlKeyValue(line: String): ParsedLine? {
        if (line.isBlank()) return null
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("#")) return null

        val separatorIndex = findSeparatorOutsideQuotes(line, '=')
        if (separatorIndex <= 0) return null

        val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        if (separatorIndex <= indent) return null

        val rawKey = line.substring(indent, separatorIndex).trimEnd()
        if (rawKey.isBlank()) return null

        val normalizedKey = rawKey.trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")

        if (normalizedKey.isBlank()) return null

        val valuePart = line.substring(separatorIndex + 1)
        return ParsedLine(indent, rawKey, normalizedKey, valuePart)
    }

    private fun findSeparatorOutsideQuotes(line: String, separator: Char): Int {
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false

        for (index in line.indices) {
            val ch = line[index]
            if (escaped) {
                escaped = false
                continue
            }

            if (ch == '\\' && inDoubleQuote) {
                escaped = true
                continue
            }

            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                continue
            }

            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && ch == separator) {
                return index
            }
        }

        return -1
    }

    private fun escapeYamlSingleQuoted(value: String): String {
        return value.replace("'", "''")
    }

    private fun escapeTomlBasicString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private data class ParsedLine(
        val indent: Int,
        val rawKey: String,
        val normalizedKey: String,
        val valuePart: String
    )
}
