package com.github.yelog.i18nhelper.util

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

    fun isLocaleName(name: String): Boolean {
        return localePatterns.any { it.matches(name) }
    }

    fun normalizeLocale(name: String): String {
        val parts = name.replace('-', '_').split('_')
        return when (parts.size) {
            1 -> parts[0].lowercase()
            else -> parts[0].lowercase() + "_" + parts[1].uppercase()
        }
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
