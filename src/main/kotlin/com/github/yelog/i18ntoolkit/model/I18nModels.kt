package com.github.yelog.i18ntoolkit.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

enum class I18nFramework(val displayName: String, val packageNames: List<String>) {
    VUE_I18N("vue-i18n", listOf("vue-i18n")),
    REACT_I18NEXT("react-i18next", listOf("react-i18next", "i18next")),
    I18NEXT("i18next", listOf("i18next")),
    NEXT_INTL("next-intl", listOf("next-intl")),
    NUXT_I18N("@nuxtjs/i18n", listOf("@nuxtjs/i18n")),
    REACT_INTL("react-intl", listOf("react-intl")),
    SPRING_MESSAGE("spring message", listOf("spring-boot-starter", "spring-context")),
    UNKNOWN("unknown", emptyList());

    companion object {
        fun fromPackageName(name: String): I18nFramework {
            return entries.find { framework ->
                framework.packageNames.any { it == name }
            } ?: UNKNOWN
        }
    }
}

object I18nDirectories {
    val STANDARD_DIRS = listOf(
        "locales",
        "locale",
        "i18n",
        "lang",
        "langs",
        "messages",
        "translations"
    )
}

enum class TranslationFileType(val extensions: List<String>) {
    JSON(listOf("json")),
    YAML(listOf("yaml", "yml")),
    TOML(listOf("toml")),
    JAVASCRIPT(listOf("js", "mjs", "cjs")),
    TYPESCRIPT(listOf("ts", "mts", "cts")),
    PROPERTIES(listOf("properties"));

    companion object {
        fun fromExtension(ext: String): TranslationFileType? {
            return entries.find { it.extensions.contains(ext.lowercase()) }
        }

        fun allExtensions(): List<String> = entries.flatMap { it.extensions }
    }
}

data class TranslationEntry(
    val key: String,
    val value: String,
    val locale: String,
    val file: VirtualFile,
    val offset: Int,
    val length: Int
)

data class TranslationFile(
    val file: VirtualFile,
    val locale: String,
    val module: String?,
    val businessUnit: String?,
    val keyPrefix: String,
    val entries: MutableMap<String, TranslationEntry> = mutableMapOf()
)

data class TranslationData(
    val framework: I18nFramework,
    val files: MutableList<TranslationFile> = mutableListOf(),
    val translations: MutableMap<String, MutableMap<String, TranslationEntry>> = mutableMapOf()
) {
    fun getTranslation(key: String, locale: String? = null): TranslationEntry? {
        val localeMap = translations[key] ?: return null
        return if (locale != null) {
            localeMap[locale]
        } else {
            localeMap["zh_CN"] ?: localeMap["zh"] ?: localeMap["en"] ?: localeMap.values.firstOrNull()
        }
    }

    fun getAllTranslations(key: String): Map<String, TranslationEntry> {
        return translations[key] ?: emptyMap()
    }

    fun addEntry(entry: TranslationEntry) {
        translations
            .getOrPut(entry.key) { mutableMapOf() }[entry.locale] = entry
    }
}

data class I18nKeyUsage(
    val key: String,
    val element: PsiElement,
    val file: VirtualFile,
    val offset: Int
)
