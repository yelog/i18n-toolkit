package com.github.yelog.i18nhelper.util

import com.intellij.openapi.vfs.VirtualFile



object I18nKeyGenerator {

    data class PathInfo(
        val locale: String,
        val module: String?,
        val businessUnit: String?,
        val keyPrefix: String
    )

    fun parseFilePath(file: VirtualFile, projectBasePath: String): PathInfo {
        val relativePath = file.path.removePrefix(projectBasePath).removePrefix("/")
        val parts = relativePath.split("/")
        val fileName = file.nameWithoutExtension

        return when {
            isViewsLocalePattern(parts) -> parseViewsLocalePattern(parts, fileName)
            isStandardLocalePattern(parts) -> parseStandardLocalePattern(parts, fileName)
            else -> PathInfo(
                locale = extractLocale(parts, fileName),
                module = null,
                businessUnit = null,
                keyPrefix = ""
            )
        }
    }

    private fun isViewsLocalePattern(parts: List<String>): Boolean {
        return parts.contains("views") && hasLocaleDirectory(parts)
    }

    private fun isStandardLocalePattern(parts: List<String>): Boolean {
        return hasLocaleDirectory(parts)
    }

    private fun hasLocaleDirectory(parts: List<String>): Boolean {
        val localeDirectories = listOf("locales", "locale", "i18n", "lang", "langs", "messages", "translations")
        return parts.any { localeDirectories.contains(it.lowercase()) }
    }

    private fun parseViewsLocalePattern(parts: List<String>, fileName: String): PathInfo {
        val viewsIndex = parts.indexOf("views")
        val businessUnit = if (viewsIndex + 1 < parts.size) parts[viewsIndex + 1] else null

        val localeInfo = extractLocaleAndModule(parts, fileName)

        val keyPrefix = buildString {
            if (businessUnit != null && businessUnit != "locales" && !isLocale(businessUnit)) {
                append(businessUnit).append(".")
            }
            if (localeInfo.module != null) {
                append(localeInfo.module).append(".")
            }
        }

        return PathInfo(
            locale = localeInfo.locale,
            module = localeInfo.module,
            businessUnit = businessUnit?.takeIf { !isLocale(it) && it != "locales" },
            keyPrefix = keyPrefix
        )
    }

    private fun parseStandardLocalePattern(parts: List<String>, fileName: String): PathInfo {
        val localeInfo = extractLocaleAndModule(parts, fileName)

        // Don't use "message" or "messages" as module prefix (common in Spring Message)
        val shouldIncludeModule = localeInfo.module != null &&
                !localeInfo.module.equals("message", ignoreCase = true) &&
                !localeInfo.module.equals("messages", ignoreCase = true)

        val keyPrefix = if (shouldIncludeModule) "${localeInfo.module}." else ""

        return PathInfo(
            locale = localeInfo.locale,
            module = if (shouldIncludeModule) localeInfo.module else null,
            businessUnit = null,
            keyPrefix = keyPrefix
        )
    }

    private data class LocaleModuleInfo(val locale: String, val module: String?)

    private fun extractLocaleAndModule(parts: List<String>, fileName: String): LocaleModuleInfo {
        val localeDirectoryNames = listOf("locales", "locale", "i18n", "lang", "langs", "messages", "translations")
        val localeIndex = parts.indexOfLast { localeDirectoryNames.contains(it.lowercase()) }

        if (localeIndex == -1) {
            return LocaleModuleInfo(locale = fileName, module = null)
        }

        val afterLocaleParts = parts.drop(localeIndex + 1).dropLast(1)

        return when {
            afterLocaleParts.isEmpty() -> {
                if (isLocale(fileName)) {
                    LocaleModuleInfo(locale = fileName, module = null)
                } else {
                    LocaleModuleInfo(locale = "unknown", module = fileName)
                }
            }
            afterLocaleParts.size == 1 && isLocale(afterLocaleParts[0]) -> {
                LocaleModuleInfo(locale = afterLocaleParts[0], module = fileName)
            }
            afterLocaleParts.size == 1 && isLocale(fileName) -> {
                LocaleModuleInfo(locale = fileName, module = null)
            }
            afterLocaleParts.size >= 1 -> {
                val locale = afterLocaleParts.find { isLocale(it) }
                    ?: if (isLocale(fileName)) fileName else "unknown"
                val module = if (isLocale(fileName)) null else fileName
                LocaleModuleInfo(locale = locale, module = module)
            }
            else -> {
                LocaleModuleInfo(locale = fileName, module = null)
            }
        }
    }

    private fun extractLocale(parts: List<String>, fileName: String): String {
        for (part in parts.reversed()) {
            if (isLocale(part)) return part
        }
        return if (isLocale(fileName)) fileName else "unknown"
    }

    private fun isLocale(name: String): Boolean = I18nLocaleUtils.isLocaleName(name)

    fun buildFullKey(prefix: String, key: String): String {
        return if (prefix.isEmpty()) key else "$prefix$key"
    }
}
