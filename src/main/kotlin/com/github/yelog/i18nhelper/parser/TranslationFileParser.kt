package com.github.yelog.i18nhelper.parser

import com.github.yelog.i18nhelper.model.TranslationEntry
import com.github.yelog.i18nhelper.model.TranslationFileType
import com.github.yelog.i18nhelper.util.I18nKeyGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.yaml.snakeyaml.Yaml
import java.io.StringReader
import java.util.*

object TranslationFileParser {

    fun parse(
        project: Project,
        file: VirtualFile,
        keyPrefix: String,
        locale: String
    ): Map<String, TranslationEntry> {
        val ext = file.extension?.lowercase() ?: return emptyMap()
        val fileType = TranslationFileType.fromExtension(ext) ?: return emptyMap()

        return when (fileType) {
            TranslationFileType.JSON -> parseJsonFile(project, file, keyPrefix, locale)
            TranslationFileType.YAML -> parseYamlFile(file, keyPrefix, locale)
            TranslationFileType.JAVASCRIPT, TranslationFileType.TYPESCRIPT -> parseJsFile(project, file, keyPrefix, locale)
            TranslationFileType.PROPERTIES -> parsePropertiesFile(file, keyPrefix, locale)
            TranslationFileType.TOML -> parseTomlFile(file, keyPrefix, locale)
        }
    }

    private fun parseJsonFile(
        project: Project,
        file: VirtualFile,
        keyPrefix: String,
        locale: String
    ): Map<String, TranslationEntry> {
        val entries = mutableMapOf<String, TranslationEntry>()
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return entries
        val rootObject = psiFile.topLevelValue as? JsonObject ?: return entries

        parseJsonObject(rootObject, keyPrefix, locale, file, entries)
        return entries
    }

    private fun parseJsonObject(
        obj: JsonObject,
        currentPrefix: String,
        locale: String,
        file: VirtualFile,
        entries: MutableMap<String, TranslationEntry>
    ) {
        obj.propertyList.forEach { property ->
            val key = property.name
            val fullKey = if (currentPrefix.isEmpty()) key else "$currentPrefix$key"
            
            when (val value = property.value) {
                is JsonStringLiteral -> {
                    entries[fullKey] = TranslationEntry(
                        key = fullKey,
                        value = value.value,
                        locale = locale,
                        file = file,
                        offset = property.nameElement.textOffset,
                        length = property.nameElement.textLength
                    )
                }
                is JsonObject -> {
                    parseJsonObject(value, "$fullKey.", locale, file, entries)
                }
            }
        }
    }

    private fun parseYamlFile(
        file: VirtualFile,
        keyPrefix: String,
        locale: String
    ): Map<String, TranslationEntry> {
        val entries = mutableMapOf<String, TranslationEntry>()
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(StringReader(content)) ?: return entries
            parseYamlMap(data, keyPrefix, locale, file, entries, 0)
        } catch (_: Exception) {
        }
        return entries
    }

    private fun parseYamlMap(
        map: Map<String, Any>,
        currentPrefix: String,
        locale: String,
        file: VirtualFile,
        entries: MutableMap<String, TranslationEntry>,
        estimatedOffset: Int
    ) {
        var offset = estimatedOffset
        map.forEach { (key, value) ->
            val fullKey = if (currentPrefix.isEmpty()) key else "$currentPrefix$key"
            when (value) {
                is String -> {
                    entries[fullKey] = TranslationEntry(
                        key = fullKey,
                        value = value,
                        locale = locale,
                        file = file,
                        offset = offset,
                        length = key.length
                    )
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    parseYamlMap(value as Map<String, Any>, "$fullKey.", locale, file, entries, offset)
                }
            }
            offset += key.length + value.toString().length + 3
        }
    }

    private fun parseJsFile(
        project: Project,
        file: VirtualFile,
        keyPrefix: String,
        locale: String
    ): Map<String, TranslationEntry> {
        val entries = mutableMapOf<String, TranslationEntry>()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return entries

        psiFile.children.forEach { element ->
            when (element) {
                is JSExpressionStatement -> {
                    parseJsExpression(element.expression, keyPrefix, locale, file, entries)
                }
                is JSVarStatement -> {
                    element.variables.forEach { variable ->
                        variable.initializer?.let { init ->
                            parseJsExpression(init, keyPrefix, locale, file, entries)
                        }
                    }
                }
                is ES6ExportDefaultAssignment -> {
                    parseJsExpression(element.expression, keyPrefix, locale, file, entries)
                }
            }
        }

        findExportDefault(psiFile)?.let { exportExpr ->
            parseJsExpression(exportExpr, keyPrefix, locale, file, entries)
        }

        return entries
    }

    private fun findExportDefault(psiFile: com.intellij.psi.PsiFile): JSExpression? {
        var result: JSExpression? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element is ES6ExportDefaultAssignment) {
                    result = element.expression
                    return
                }
                super.visitElement(element)
            }
        })
        return result
    }

    private fun parseJsExpression(
        expr: JSExpression?,
        currentPrefix: String,
        locale: String,
        file: VirtualFile,
        entries: MutableMap<String, TranslationEntry>
    ) {
        when (expr) {
            is JSObjectLiteralExpression -> {
                expr.properties.forEach { prop ->
                    val key = prop.name ?: return@forEach
                    val fullKey = if (currentPrefix.isEmpty()) key else "$currentPrefix$key"
                    
                    when (val value = prop.value) {
                        is JSLiteralExpression -> {
                            val stringValue = value.stringValue
                            if (stringValue != null) {
                                entries[fullKey] = TranslationEntry(
                                    key = fullKey,
                                    value = stringValue,
                                    locale = locale,
                                    file = file,
                                    offset = prop.textOffset,
                                    length = key.length
                                )
                            }
                        }
                        is JSObjectLiteralExpression -> {
                            parseJsExpression(value, "$fullKey.", locale, file, entries)
                        }
                    }
                }
            }
        }
    }

    private fun parsePropertiesFile(
        file: VirtualFile,
        keyPrefix: String,
        locale: String
    ): Map<String, TranslationEntry> {
        val entries = mutableMapOf<String, TranslationEntry>()
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val properties = Properties()
            properties.load(StringReader(content))
            
            var offset = 0
            content.lines().forEach { line ->
                if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
                    val equalIndex = line.indexOf('=')
                    if (equalIndex > 0) {
                        val key = line.substring(0, equalIndex).trim()
                        val value = line.substring(equalIndex + 1).trim()
                        val fullKey = if (keyPrefix.isEmpty()) key else "$keyPrefix$key"
                        
                        entries[fullKey] = TranslationEntry(
                            key = fullKey,
                            value = value,
                            locale = locale,
                            file = file,
                            offset = offset,
                            length = key.length
                        )
                    }
                }
                offset += line.length + 1
            }
        } catch (_: Exception) {
        }
        return entries
    }

    private fun parseTomlFile(
        file: VirtualFile,
        keyPrefix: String,
        locale: String
    ): Map<String, TranslationEntry> {
        val entries = mutableMapOf<String, TranslationEntry>()
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val toml = com.moandjiezana.toml.Toml().read(content)
            parseTomlMap(toml.toMap(), keyPrefix, locale, file, entries, 0)
        } catch (_: Exception) {
        }
        return entries
    }

    private fun parseTomlMap(
        map: Map<String, Any>,
        currentPrefix: String,
        locale: String,
        file: VirtualFile,
        entries: MutableMap<String, TranslationEntry>,
        estimatedOffset: Int
    ) {
        var offset = estimatedOffset
        map.forEach { (key, value) ->
            val fullKey = if (currentPrefix.isEmpty()) key else "$currentPrefix$key"
            when (value) {
                is String -> {
                    entries[fullKey] = TranslationEntry(
                        key = fullKey,
                        value = value,
                        locale = locale,
                        file = file,
                        offset = offset,
                        length = key.length
                    )
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    parseTomlMap(value as Map<String, Any>, "$fullKey.", locale, file, entries, offset)
                }
            }
            offset += key.length + value.toString().length + 5
        }
    }
}
