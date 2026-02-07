package com.github.yelog.i18ntoolkit.util

object I18nLocaleUtils {

    private val localePatterns = listOf(
        Regex("^[a-z]{2}$"),
        Regex("^[a-z]{2}[_-][A-Z]{2}$"),
        Regex("^[a-z]{2}[_-][a-z]{2}$"),
        Regex("^zh[_-]CN$", RegexOption.IGNORE_CASE),
        Regex("^zh[_-]TW$", RegexOption.IGNORE_CASE),
        Regex("^zh[_-]HK$", RegexOption.IGNORE_CASE),
        Regex("^en[_-]US$", RegexOption.IGNORE_CASE),
        Regex("^en[_-]GB$", RegexOption.IGNORE_CASE),
        Regex("^ja[_-]JP$", RegexOption.IGNORE_CASE),
        Regex("^ko[_-]KR$", RegexOption.IGNORE_CASE)
    )

    /**
     * Pattern for Spring message bundle filenames with locale suffix:
     * messages_zh_CN, messages_en, messages-en-US, etc.
     */
    private val SPRING_BASENAME_LOCALE_PATTERN =
        Regex("^messages[_-]([a-zA-Z]{2}(?:[_-][a-zA-Z]{2})?)$", RegexOption.IGNORE_CASE)

    fun isLocaleName(name: String): Boolean {
        return localePatterns.any { it.matches(name) }
    }

    /**
     * Extract locale from a Spring message bundle filename.
     * e.g., "messages_zh_CN" → "zh_CN", "messages_en" → "en", "messages" → null
     */
    fun extractLocaleFromSpringFilename(fileNameWithoutExtension: String): String? {
        val match = SPRING_BASENAME_LOCALE_PATTERN.matchEntire(fileNameWithoutExtension)
        val locale = match?.groupValues?.get(1) ?: return null
        return normalizeLocale(locale)
    }

    fun normalizeLocale(name: String): String {
        val parts = name.replace('-', '_').split('_')
        return when (parts.size) {
            1 -> parts[0].lowercase()
            else -> parts[0].lowercase() + "_" + parts[1].uppercase()
        }
    }

    fun formatLocaleForDisplay(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.contains('-') || trimmed.contains('_')) {
            trimmed
        } else {
            trimmed.lowercase()
        }
    }

    fun chooseDisplayLocale(locales: Collection<String>): String {
        if (locales.isEmpty()) return ""
        val preferred = locales.firstOrNull { it.contains('-') }
            ?: locales.firstOrNull { it.contains('_') }
            ?: locales.first()
        return formatLocaleForDisplay(preferred)
    }

    fun buildLocaleCandidates(locale: String): List<String> {
        if (locale.isBlank()) return emptyList()
        val normalized = normalizeLocale(locale)
        val parts = normalized.split('_')
        val language = parts[0]
        val withDash = normalized.replace('_', '-')
        return listOf(locale, normalized, withDash, language).distinct()
    }
}
