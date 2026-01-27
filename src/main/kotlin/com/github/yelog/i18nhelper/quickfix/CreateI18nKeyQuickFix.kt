package com.github.yelog.i18nhelper.quickfix

import com.github.yelog.i18nhelper.model.TranslationFile
import com.github.yelog.i18nhelper.model.TranslationFileType
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.github.yelog.i18nhelper.util.I18nLocaleUtils
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.json.psi.*
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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

    /**
     * Find target translation files for the given key using prefix ancestry and sibling key lookup.
     */
    private fun findTargetFiles(cacheService: I18nCacheService, fullKey: String): List<TranslationFile> {
        val allFiles = cacheService.getTranslationFiles()
        if (allFiles.isEmpty()) return emptyList()

        // Exclude aggregator files whose filename is a locale name (e.g. zh_CN.ts, en_US.ts).
        // These are entry-point files that re-export translations, not direct translation modules.
        val candidateFiles = allFiles.filter { !I18nLocaleUtils.isLocaleName(it.file.nameWithoutExtension) }

        // Step 1: Find files whose keyPrefix matches the start of the full key.
        // Pick the longest (most specific) matching prefix.
        val prefixMatches = candidateFiles.filter { it.keyPrefix.isNotEmpty() && fullKey.startsWith(it.keyPrefix) }
        if (prefixMatches.isNotEmpty()) {
            val maxPrefixLen = prefixMatches.maxOf { it.keyPrefix.length }
            return prefixMatches.filter { it.keyPrefix.length == maxPrefixLen }
        }

        // Step 2: Sibling key lookup — progressively strip last segment of the key
        val parts = fullKey.split(".")
        for (i in parts.size - 1 downTo 1) {
            val prefix = parts.subList(0, i).joinToString(".") + "."
            val siblingKeys = cacheService.findKeysByPrefix(prefix)
            if (siblingKeys.isNotEmpty()) {
                // Get the files that contain these sibling keys
                val filesFromEntries = mutableSetOf<VirtualFile>()
                for (siblingKey in siblingKeys) {
                    val entries = cacheService.getEntriesForKey(siblingKey)
                    entries.forEach { filesFromEntries.add(it.file) }
                }
                // Map back to TranslationFile objects
                val matched = candidateFiles.filter { it.file in filesFromEntries }
                if (matched.isNotEmpty()) {
                    // Apply most-specific prefix filter: prefer files with the longest keyPrefix
                    val maxPrefixLen = matched.maxOf { it.keyPrefix.length }
                    val mostSpecific = matched.filter { it.keyPrefix.length == maxPrefixLen }
                    return mostSpecific
                }
            }
        }

        // Step 3: No match found
        return emptyList()
    }

    private fun createKeyInTranslationFiles(project: Project, fullKey: String) {
        val cacheService = I18nCacheService.getInstance(project)
        val targetFiles = findTargetFiles(cacheService, fullKey)

        if (targetFiles.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("I18n Helper")
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

            if (offset != null) {
                if (firstCreatedResult == null) {
                    firstCreatedResult = virtualFile to offset
                }
                if (displayLocale != null && translationFile.locale == displayLocale && displayLocaleResult == null) {
                    displayLocaleResult = virtualFile to offset
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
                    val doc = editor.document
                    val text = doc.text
                    // For JSON: offset points to opening quote of value "", select between quotes
                    // For JS/TS: offset points to opening quote of value '', select between quotes
                    // For Properties: offset points to the = sign, select after =
                    if (offset < text.length) {
                        val ch = text[offset]
                        if (ch == '"' || ch == '\'') {
                            // Place caret between the quotes
                            editor.caretModel.moveToOffset(offset + 1)
                        } else {
                            editor.caretModel.moveToOffset(offset)
                        }
                    }
                }
            }
        }

        // Refresh the cache
        cacheService.refresh()
    }

    private fun createKeyInJsonFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? JsonFile ?: return@runWriteCommandAction null
                val rootObject = psiFile.topLevelValue as? JsonObject ?: return@runWriteCommandAction null

                val keyParts = key.split(".")
                var currentObject = rootObject

                // Navigate/create nested structure
                for (i in 0 until keyParts.size - 1) {
                    val part = keyParts[i]
                    val existingProperty = currentObject.findProperty(part)

                    if (existingProperty != null) {
                        val value = existingProperty.value
                        if (value is JsonObject) {
                            currentObject = value
                        } else {
                            return@runWriteCommandAction null
                        }
                    } else {
                        val newObject = createJsonObject(project, "{}")
                        val newProperty = createJsonProperty(project, part, newObject)
                        currentObject = addPropertyToObject(project, currentObject, newProperty) ?: return@runWriteCommandAction null
                    }
                }

                // Add the final key with empty value
                val finalKey = keyParts.last()
                val existingProperty = currentObject.findProperty(finalKey)

                if (existingProperty == null) {
                    val newValue = createJsonStringLiteral(project, "")
                    val newProperty = createJsonProperty(project, finalKey, newValue)
                    addPropertyToObject(project, currentObject, newProperty)

                    PsiDocumentManager.getInstance(project).commitAllDocuments()

                    // Re-find the property to get its final offset
                    val addedProperty = currentObject.findProperty(finalKey)
                    val valueElement = addedProperty?.value
                    // Return offset of the opening quote of the value
                    valueElement?.textRange?.startOffset
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun createKeyInPropertiesFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                if (content.lines().any { it.trim().startsWith("$key=") || it.trim().startsWith("$key ") }) {
                    return@runWriteCommandAction null
                }

                // Find best insertion point: after last line sharing the longest common prefix
                val keyPrefixParts = key.split(".")
                var bestInsertOffset = document.textLength
                var bestMatchLen = -1

                val lines = content.lines()
                var currentOffset = 0
                for (line in lines) {
                    val lineEnd = currentOffset + line.length
                    val trimmed = line.trim()
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val lineKey = trimmed.substring(0, eqIndex).trim()
                        val lineKeyParts = lineKey.split(".")
                        // Count common prefix parts
                        var common = 0
                        for (j in lineKeyParts.indices) {
                            if (j < keyPrefixParts.size - 1 && lineKeyParts[j] == keyPrefixParts[j]) {
                                common++
                            } else break
                        }
                        if (common > bestMatchLen) {
                            bestMatchLen = common
                            bestInsertOffset = lineEnd
                        } else if (common == bestMatchLen && common > 0) {
                            bestInsertOffset = lineEnd
                        }
                    }
                    currentOffset = lineEnd + 1 // +1 for newline
                }

                val newLine = if (bestInsertOffset < document.textLength && bestInsertOffset > 0) "\n" else
                    if (content.isNotEmpty() && !content.endsWith("\n")) "\n" else ""
                val entry = "$newLine$key=\n"

                document.insertString(bestInsertOffset, entry)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                // Return offset of position right after '='
                bestInsertOffset + newLine.length + key.length + 1
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun createKeyInJsFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                val keyParts = key.split(".")

                // Find the export default or first object
                val exportDefaultStart = content.indexOf("export default")
                val objStart = if (exportDefaultStart != -1) {
                    content.indexOf("{", exportDefaultStart)
                } else {
                    content.indexOf("{")
                }
                if (objStart == -1) return@runWriteCommandAction null

                // Navigate existing structure to find deepest existing parent
                var searchStart = objStart
                var depth = 0
                var existingDepth = 0

                for (i in keyParts.indices) {
                    if (i == keyParts.lastIndex) break
                    val part = keyParts[i]
                    // Look for this key in the current scope
                    val keyPattern = Regex("""(?:['"]?${Regex.escape(part)}['"]?)\s*:\s*\{""")
                    val match = keyPattern.find(content, searchStart)
                    if (match != null && isInCurrentScope(content, objStart, match.range.first, depth)) {
                        searchStart = match.range.last
                        depth++
                        existingDepth = i + 1
                    } else {
                        break
                    }
                }

                // Find insertion point: before the closing brace of the current scope
                val insertBrace = findClosingBrace(content, searchStart)
                    ?: return@runWriteCommandAction null

                val remainingParts = keyParts.subList(existingDepth, keyParts.size)

                // Check if the first remaining key already exists in this scope
                if (remainingParts.isNotEmpty()) {
                    val firstPart = remainingParts.first()
                    val existsPattern = Regex("""['"]?${Regex.escape(firstPart)}['"]?\s*:""")
                    val scopeContent = content.substring(searchStart, insertBrace)
                    if (existsPattern.containsMatchIn(scopeContent)) {
                        return@runWriteCommandAction null
                    }
                }
                val baseIndent = "  ".repeat(existingDepth + 1)

                val propertyText = buildString {
                    for (i in remainingParts.indices) {
                        val indent = "  ".repeat(existingDepth + 1 + i)
                        if (i > 0) append("\n")
                        append(indent)
                        append(remainingParts[i])
                        append(": ")
                        if (i == remainingParts.lastIndex) {
                            append("''")
                        } else {
                            append("{")
                        }
                    }
                    // Close nested objects
                    for (i in remainingParts.size - 2 downTo 0) {
                        append("\n")
                        append("  ".repeat(existingDepth + 1 + i))
                        append("}")
                    }
                }

                // Find the last non-whitespace character before the closing brace
                var lastContentPos = insertBrace - 1
                while (lastContentPos >= searchStart && content[lastContentPos].isWhitespace()) {
                    lastContentPos--
                }

                val needsComma = lastContentPos >= searchStart &&
                        content[lastContentPos] != '{' && content[lastContentPos] != ','

                // Indent for the closing brace of the current scope
                val closingBraceIndent = "  ".repeat(existingDepth)

                val textToInsert = buildString {
                    if (needsComma) append(",")
                    append("\n")
                    append(propertyText)
                    append("\n")
                    append(closingBraceIndent)
                }

                // Replace whitespace span between last content and closing brace
                val replaceStart = lastContentPos + 1
                document.replaceString(replaceStart, insertBrace, textToInsert)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                // Return offset of the opening quote of the empty value
                val valueKeyword = remainingParts.last() + ": '"
                val valuePos = textToInsert.lastIndexOf(valueKeyword)
                if (valuePos != -1) {
                    replaceStart + valuePos + valueKeyword.length - 1
                } else {
                    replaceStart
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun isInCurrentScope(content: String, scopeStart: Int, position: Int, expectedDepth: Int): Boolean {
        var depth = 0
        for (i in scopeStart..minOf(position, content.length - 1)) {
            when (content[i]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth == expectedDepth + 1
    }

    private fun findClosingBrace(content: String, start: Int): Int? {
        var depth = 0
        var foundOpen = false
        for (i in start until content.length) {
            when (content[i]) {
                '{' -> { depth++; foundOpen = true }
                '}' -> {
                    depth--
                    if (foundOpen && depth == 0) return i
                }
            }
        }
        return null
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
            obj.add(property)
        } else {
            val lastProperty = propertyList.last()
            obj.addAfter(property, lastProperty)

            val comma = JsonElementGenerator(project).createComma()
            obj.addAfter(comma, lastProperty)
        }

        val addedProperty = obj.findProperty(property.name)
        return addedProperty?.value as? JsonObject
    }
}
