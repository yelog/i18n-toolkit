package com.github.yelog.i18ntoolkit.util

import com.github.yelog.i18ntoolkit.model.TranslationFile
import com.github.yelog.i18ntoolkit.model.TranslationFileType
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

object I18nKeyCreationSupport {

    /**
     * Find target translation files for the given key using prefix ancestry and sibling key lookup.
     */
    fun findTargetFiles(cacheService: I18nCacheService, fullKey: String): List<TranslationFile> {
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
                val filesFromEntries = mutableSetOf<VirtualFile>()
                for (siblingKey in siblingKeys) {
                    cacheService.getEntriesForKey(siblingKey).forEach { filesFromEntries.add(it.file) }
                }
                val matched = candidateFiles.filter { it.file in filesFromEntries }
                if (matched.isNotEmpty()) {
                    val maxPrefixLen = matched.maxOf { it.keyPrefix.length }
                    return matched.filter { it.keyPrefix.length == maxPrefixLen }
                }
            }
        }

        // Step 3: No match found
        return emptyList()
    }

    /**
     * Create key in one translation file. Returns offset of the opening quote of created value.
     */
    fun createKeyInTranslationFile(
        project: Project,
        translationFile: TranslationFile,
        fullKey: String,
        initialValue: String = ""
    ): Int? {
        if (translationFile.entries.containsKey(fullKey)) return null

        val keyToCreate = if (translationFile.keyPrefix.isNotEmpty() && fullKey.startsWith(translationFile.keyPrefix)) {
            fullKey.removePrefix(translationFile.keyPrefix)
        } else {
            fullKey
        }

        val virtualFile = translationFile.file
        val fileType = TranslationFileType.fromExtension(virtualFile.extension ?: "")

        return when (fileType) {
            TranslationFileType.JSON -> createKeyInJsonFile(project, virtualFile, keyToCreate, initialValue)
            TranslationFileType.PROPERTIES -> createKeyInPropertiesFile(project, virtualFile, keyToCreate, initialValue)
            TranslationFileType.JAVASCRIPT, TranslationFileType.TYPESCRIPT ->
                createKeyInJsFile(project, virtualFile, keyToCreate, initialValue)
            TranslationFileType.YAML -> createKeyInYamlFile(project, virtualFile, keyToCreate, initialValue)
            TranslationFileType.TOML -> createKeyInTomlFile(project, virtualFile, keyToCreate, initialValue)
            else -> null
        }
    }

    private fun createKeyInJsonFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String,
        initialValue: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? JsonFile ?: return@runWriteCommandAction null
                val rootObject = psiFile.topLevelValue as? JsonObject ?: return@runWriteCommandAction null

                val keyParts = key.split(".")
                var currentObject = rootObject

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

                val finalKey = keyParts.last()
                val existingProperty = currentObject.findProperty(finalKey)
                if (existingProperty != null) return@runWriteCommandAction null

                val newValue = createJsonStringLiteral(project, initialValue)
                val newProperty = createJsonProperty(project, finalKey, newValue)
                addPropertyToObject(project, currentObject, newProperty)

                PsiDocumentManager.getInstance(project).commitAllDocuments()

                val addedProperty = currentObject.findProperty(finalKey)
                val valueElement = addedProperty?.value
                valueElement?.textRange?.startOffset
            } catch (e: Exception) {
            thisLogger().warn("I18n Toolkit: Key creation operation failed", e)
                null
            }
        }
    }

    private fun createKeyInPropertiesFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String,
        initialValue: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                if (content.lines().any { it.trim().startsWith("$key=") || it.trim().startsWith("$key ") }) {
                    return@runWriteCommandAction null
                }

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
                        var common = 0
                        for (j in lineKeyParts.indices) {
                            if (j < keyPrefixParts.size - 1 && lineKeyParts[j] == keyPrefixParts[j]) {
                                common++
                            } else {
                                break
                            }
                        }
                        if (common > bestMatchLen) {
                            bestMatchLen = common
                            bestInsertOffset = lineEnd
                        } else if (common == bestMatchLen && common > 0) {
                            bestInsertOffset = lineEnd
                        }
                    }
                    currentOffset = lineEnd + 1
                }

                val newLine = if (bestInsertOffset < document.textLength && bestInsertOffset > 0) {
                    "\n"
                } else if (content.isNotEmpty() && !content.endsWith("\n")) {
                    "\n"
                } else {
                    ""
                }

                val escapedValue = escapePropertiesValue(initialValue)
                val entry = "$newLine$key=$escapedValue\n"

                document.insertString(bestInsertOffset, entry)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)

                bestInsertOffset + newLine.length + key.length + 1
            } catch (e: Exception) {
            thisLogger().warn("I18n Toolkit: Key creation operation failed", e)
                null
            }
        }
    }

    private fun createKeyInJsFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String,
        initialValue: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                val keyParts = key.split(".")

                val exportDefaultStart = content.indexOf("export default")
                val objStart = if (exportDefaultStart != -1) {
                    content.indexOf("{", exportDefaultStart)
                } else {
                    content.indexOf("{")
                }
                if (objStart == -1) return@runWriteCommandAction null

                var searchStart = objStart
                var depth = 0
                var existingDepth = 0

                for (i in keyParts.indices) {
                    if (i == keyParts.lastIndex) break
                    val part = keyParts[i]
                    val keyPattern = Regex("""(?:['\"]?${Regex.escape(part)}['\"]?)\\s*:\\s*\\{""")
                    val match = keyPattern.find(content, searchStart)
                    if (match != null && isInCurrentScope(content, objStart, match.range.first, depth)) {
                        searchStart = match.range.last
                        depth++
                        existingDepth = i + 1
                    } else {
                        break
                    }
                }

                val insertBrace = findClosingBrace(content, searchStart) ?: return@runWriteCommandAction null
                val remainingParts = keyParts.subList(existingDepth, keyParts.size)
                if (remainingParts.isEmpty()) return@runWriteCommandAction null

                val firstPart = remainingParts.first()
                val existsPattern = Regex("""['\"]?${Regex.escape(firstPart)}['\"]?\\s*:""")
                val scopeContent = content.substring(searchStart, insertBrace)
                if (existsPattern.containsMatchIn(scopeContent)) {
                    return@runWriteCommandAction null
                }

                val escapedValue = escapeJsSingleQuoted(initialValue)
                val propertyText = buildString {
                    for (i in remainingParts.indices) {
                        val indent = "  ".repeat(existingDepth + 1 + i)
                        if (i > 0) append("\n")
                        append(indent)
                        append(remainingParts[i])
                        append(": ")
                        if (i == remainingParts.lastIndex) {
                            append("'")
                            append(escapedValue)
                            append("'")
                        } else {
                            append("{")
                        }
                    }
                    for (i in remainingParts.size - 2 downTo 0) {
                        append("\n")
                        append("  ".repeat(existingDepth + 1 + i))
                        append("}")
                    }
                }

                var lastContentPos = insertBrace - 1
                while (lastContentPos >= searchStart && content[lastContentPos].isWhitespace()) {
                    lastContentPos--
                }

                val needsComma = lastContentPos >= searchStart &&
                    content[lastContentPos] != '{' && content[lastContentPos] != ','

                val closingBraceIndent = "  ".repeat(existingDepth)
                val textToInsert = buildString {
                    if (needsComma) append(",")
                    append("\n")
                    append(propertyText)
                    append("\n")
                    append(closingBraceIndent)
                }

                val replaceStart = lastContentPos + 1
                document.replaceString(replaceStart, insertBrace, textToInsert)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                val valueKeyword = remainingParts.last() + ": '"
                val valuePos = textToInsert.lastIndexOf(valueKeyword)
                if (valuePos != -1) {
                    replaceStart + valuePos + valueKeyword.length - 1
                } else {
                    replaceStart
                }
            } catch (e: Exception) {
            thisLogger().warn("I18n Toolkit: Key creation operation failed", e)
                null
            }
        }
    }

    private fun createKeyInYamlFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String,
        initialValue: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                val insertOffset = document.textLength
                val prefix = if (content.isNotEmpty() && !content.endsWith("\n")) "\n" else ""
                val entry = "$prefix$key: '${escapeYamlSingleQuoted(initialValue)}'\n"

                document.insertString(insertOffset, entry)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)

                insertOffset + prefix.length + key.length + 2
            } catch (e: Exception) {
            thisLogger().warn("I18n Toolkit: Key creation operation failed", e)
                null
            }
        }
    }

    private fun createKeyInTomlFile(
        project: Project,
        virtualFile: VirtualFile,
        key: String,
        initialValue: String
    ): Int? {
        return WriteCommandAction.runWriteCommandAction<Int?>(project) {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    ?: return@runWriteCommandAction null

                val content = document.text
                val insertOffset = document.textLength
                val prefix = if (content.isNotEmpty() && !content.endsWith("\n")) "\n" else ""
                val entry = "$prefix$key = \"${escapeTomlBasicString(initialValue)}\"\n"

                document.insertString(insertOffset, entry)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)

                insertOffset + prefix.length + key.length + 3
            } catch (e: Exception) {
            thisLogger().warn("I18n Toolkit: Key creation operation failed", e)
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
                '{' -> {
                    depth++
                    foundOpen = true
                }
                '}' -> {
                    depth--
                    if (foundOpen && depth == 0) return i
                }
            }
        }
        return null
    }

    private fun createJsonObject(project: Project, text: String): JsonObject {
        val file = JsonElementGenerator(project).createDummyFile(text) as JsonFile
        return file.topLevelValue as JsonObject
    }

    private fun createJsonStringLiteral(project: Project, value: String): JsonStringLiteral {
        val property = createJsonProperty(project, "dummy", value)
        return property.value as JsonStringLiteral
    }

    private fun createJsonProperty(project: Project, name: String, value: String): JsonProperty {
        val escapedName = escapeJsonString(name)
        val escapedValue = escapeJsonString(value)
        val text = "{ \"$escapedName\": \"$escapedValue\" }"
        val file = JsonElementGenerator(project).createDummyFile(text) as JsonFile
        val obj = file.topLevelValue as JsonObject
        return obj.propertyList.first()
    }

    private fun createJsonProperty(project: Project, name: String, value: JsonValue): JsonProperty {
        val escapedName = escapeJsonString(name)
        val valueText = value.text
        val text = "{ \"$escapedName\": $valueText }"
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

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun escapeJsSingleQuoted(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
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

    private fun escapePropertiesValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("=", "\\=")
            .replace(":", "\\:")
    }
}
