package com.github.yelog.i18nhelper.quickfix

import com.github.yelog.i18nhelper.model.TranslationFileType
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.json.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

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
        ApplicationManager.getApplication().invokeLater {
            createKeyInTranslationFiles(project, key)
        }
    }

    override fun startInWriteAction(): Boolean = false

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

    private fun createKeyInTranslationFiles(project: Project, fullKey: String) {
        val cacheService = I18nCacheService.getInstance(project)
        val translationFiles = cacheService.getTranslationFiles()

        if (translationFiles.isEmpty()) {
            return
        }

        // Find files that should contain this key based on prefix
        val targetFiles = translationFiles.filter { translationFile ->
            fullKey.startsWith(translationFile.keyPrefix)
        }.ifEmpty {
            // If no files match by prefix, use all files
            translationFiles
        }

        var firstCreatedOffset: Pair<com.intellij.openapi.vfs.VirtualFile, Int>? = null

        for (translationFile in targetFiles) {
            val virtualFile = translationFile.file
            val fileType = TranslationFileType.fromExtension(virtualFile.extension ?: "")

            val keyToCreate = if (translationFile.keyPrefix.isNotEmpty() && fullKey.startsWith(translationFile.keyPrefix)) {
                fullKey.removePrefix(translationFile.keyPrefix)
            } else {
                fullKey
            }

            val offset = when (fileType) {
                TranslationFileType.JSON -> createKeyInJsonFile(project, virtualFile, keyToCreate)
                TranslationFileType.PROPERTIES -> createKeyInPropertiesFile(project, virtualFile, keyToCreate)
                TranslationFileType.JAVASCRIPT, TranslationFileType.TYPESCRIPT ->
                    createKeyInJsFile(project, virtualFile, keyToCreate)
                else -> null
            }

            if (offset != null && firstCreatedOffset == null) {
                firstCreatedOffset = virtualFile to offset
            }
        }

        // Navigate to the first created key
        firstCreatedOffset?.let { (file, offset) ->
            ApplicationManager.getApplication().invokeLater {
                val descriptor = OpenFileDescriptor(project, file, offset)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        }

        // Refresh the cache
        cacheService.refresh()
    }

    private fun createKeyInJsonFile(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        key: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? JsonFile ?: return@runWriteCommandAction null
                val rootObject = psiFile.topLevelValue as? JsonObject ?: return@runWriteCommandAction null

                val keyParts = key.split(".")
                var currentObject = rootObject
                var createdOffset: Int? = null

                // Navigate/create nested structure
                for (i in 0 until keyParts.size - 1) {
                    val part = keyParts[i]
                    val existingProperty = currentObject.findProperty(part)

                    if (existingProperty != null) {
                        val value = existingProperty.value
                        if (value is JsonObject) {
                            currentObject = value
                        } else {
                            // Property exists but is not an object, cannot continue
                            return@runWriteCommandAction null
                        }
                    } else {
                        // Create new nested object
                        val newObject = createJsonObject(project, "{}")
                        val newProperty = createJsonProperty(project, part, newObject)
                        currentObject = addPropertyToObject(project, currentObject, newProperty) ?: return@runWriteCommandAction null
                    }
                }

                // Add the final key
                val finalKey = keyParts.last()
                val existingProperty = currentObject.findProperty(finalKey)

                if (existingProperty == null) {
                    val newValue = createJsonStringLiteral(project, "TODO: Add translation")
                    val newProperty = createJsonProperty(project, finalKey, newValue)
                    addPropertyToObject(project, currentObject, newProperty)
                    createdOffset = newProperty.textRange.startOffset
                }

                PsiDocumentManager.getInstance(project).commitAllDocuments()
                createdOffset
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun createKeyInPropertiesFile(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        key: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                // Check if key already exists
                val content = document.text
                if (content.lines().any { it.trim().startsWith("$key=") || it.trim().startsWith("$key ") }) {
                    return@runWriteCommandAction null
                }

                // Add the new key at the end
                val newLine = if (content.endsWith("\n") || content.isEmpty()) "" else "\n"
                val entry = "$newLine$key=TODO: Add translation\n"
                val offset = document.textLength

                document.insertString(offset, entry)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                offset + newLine.length
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun createKeyInJsFile(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        key: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                val keyParts = key.split(".")

                // Find the last closing brace of the export default object
                val exportDefaultStart = content.indexOf("export default")
                if (exportDefaultStart == -1) {
                    // Try to find object literal
                    val objStart = content.indexOf("{")
                    if (objStart == -1) return@runWriteCommandAction null
                }

                // Build the property path to insert
                val indentation = "  "
                val propertyText = buildString {
                    append("\n")
                    for (i in keyParts.indices) {
                        append(indentation.repeat(i + 1))
                        append(keyParts[i])
                        append(": ")
                        if (i == keyParts.lastIndex) {
                            append("'TODO: Add translation'")
                        } else {
                            append("{\n")
                        }
                    }
                    // Close all nested objects
                    for (i in keyParts.size - 2 downTo 0) {
                        append("\n")
                        append(indentation.repeat(i + 1))
                        append("}")
                    }
                }

                // Find the last property or opening brace
                val lastBrace = content.lastIndexOf("}")
                if (lastBrace == -1) return@runWriteCommandAction null

                // Find if there's already content (look for comma or newline before closing brace)
                val insertPosition = lastBrace
                val hasContent = content.substring(0, lastBrace).trim().endsWith(",") ||
                        content.substring(0, lastBrace).contains(":")

                val textToInsert = if (hasContent) {
                    ",$propertyText"
                } else {
                    propertyText
                }

                document.insertString(insertPosition, textToInsert)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                insertPosition + textToInsert.indexOf(keyParts.last())
            } catch (e: Exception) {
                null
            }
        }
    }

    // Helper functions for JSON manipulation
    private fun createJsonObject(project: Project, text: String): JsonObject {
        val file = JsonElementGenerator(project).createDummyFile(text) as JsonFile
        return file.topLevelValue as JsonObject
    }

    private fun createJsonStringLiteral(project: Project, value: String): JsonStringLiteral {
        val property = createJsonProperty(project, "dummy", value)
        return property.value as JsonStringLiteral
    }

    private fun createJsonProperty(project: Project, name: String, value: String): JsonProperty {
        val text = "{ \"$name\": \"$value\" }"
        val file = JsonElementGenerator(project).createDummyFile(text) as JsonFile
        val obj = file.topLevelValue as JsonObject
        return obj.propertyList.first()
    }

    private fun createJsonProperty(project: Project, name: String, value: JsonValue): JsonProperty {
        val valueText = value.text
        val text = "{ \"$name\": $valueText }"
        val file = JsonElementGenerator(project).createDummyFile(text) as JsonFile
        val obj = file.topLevelValue as JsonObject
        return obj.propertyList.first()
    }

    private fun addPropertyToObject(project: Project, obj: JsonObject, property: JsonProperty): JsonObject? {
        val propertyList = obj.propertyList

        if (propertyList.isEmpty()) {
            // Empty object, just add
            obj.add(property)
        } else {
            // Add after the last property with a comma
            val lastProperty = propertyList.last()
            obj.addAfter(property, lastProperty)

            // Add comma after the last property if it doesn't have one
            val comma = JsonElementGenerator(project).createComma()
            obj.addAfter(comma, lastProperty)
        }

        // Find the newly added property to get the nested object
        val addedProperty = obj.findProperty(property.name)
        return addedProperty?.value as? JsonObject
    }
}
