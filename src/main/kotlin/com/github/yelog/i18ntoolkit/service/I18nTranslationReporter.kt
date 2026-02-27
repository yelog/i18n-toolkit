package com.github.yelog.i18ntoolkit.service

import com.github.yelog.i18ntoolkit.model.TranslationData
import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.model.TranslationFile
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Provides translation statistics and reporting functionality.
 * Generates reports showing translation coverage, missing keys, and locale statistics.
 */
object I18nTranslationReporter {

    data class TranslationStats(
        val totalKeys: Int,
        val localeStats: Map<String, LocaleStat>,
        val missingTranslations: Map<String, List<String>>, // locale -> list of missing keys
        val orphanedKeys: List<String> // keys present in some locales but not in reference locale
    ) {
        data class LocaleStat(
            val locale: String,
            val totalKeys: Int,
            val coveragePercent: Double
        )
    }

    data class ReportConfig(
        val referenceLocale: String = "en",
        val outputDir: String = "i18n-reports",
        val includeMissingDetails: Boolean = true,
        val includeOrphanedKeys: Boolean = true
    )

    /**
     * Generate translation statistics for the project.
     */
    fun generateStats(project: Project, config: ReportConfig = ReportConfig()): TranslationStats {
        val cacheService = I18nCacheService.getInstance(project)
        val allKeys = cacheService.getAllKeys()
        val availableLocales = cacheService.getAvailableLocales()
        
        if (allKeys.isEmpty()) {
            thisLogger().info("I18n Toolkit: No translation keys found for statistics")
            return TranslationStats(0, emptyMap(), emptyMap(), emptyList())
        }

        // Determine reference locale (fallback to first available if specified not found)
        val referenceLocale = if (availableLocales.contains(config.referenceLocale)) {
            config.referenceLocale
        } else {
            availableLocales.firstOrNull() ?: return TranslationStats(0, emptyMap(), emptyMap(), emptyList())
        }

        // Get reference keys (all keys that should exist in every locale)
        val referenceKeys = allKeys.toSortedSet()
        
        // Calculate stats for each locale
        val localeStats = mutableMapOf<String, TranslationStats.LocaleStat>()
        val missingTranslations = mutableMapOf<String, List<String>>()
        
        for (locale in availableLocales) {
            val localeKeys = mutableSetOf<String>()
            for (key in referenceKeys) {
                if (cacheService.getTranslationStrict(key, locale) != null) {
                    localeKeys.add(key)
                }
            }
            
            val coveragePercent = if (referenceKeys.isEmpty()) 0.0 
                else (localeKeys.size.toDouble() / referenceKeys.size * 100)
            
            localeStats[locale] = TranslationStats.LocaleStat(
                locale = locale,
                totalKeys = localeKeys.size,
                coveragePercent = coveragePercent
            )
            
            // Find missing keys
            if (config.includeMissingDetails) {
                val missing = referenceKeys.filter { !localeKeys.contains(it) }
                if (missing.isNotEmpty()) {
                    missingTranslations[locale] = missing
                }
            }
        }

        // Find orphaned keys (keys in other locales but not in reference)
        val orphanedKeys = if (config.includeOrphanedKeys) {
            findOrphanedKeys(cacheService, referenceLocale, availableLocales, referenceKeys)
        } else {
            emptyList()
        }

        return TranslationStats(
            totalKeys = referenceKeys.size,
            localeStats = localeStats,
            missingTranslations = missingTranslations,
            orphanedKeys = orphanedKeys
        )
    }

    private fun findOrphanedKeys(
        cacheService: I18nCacheService,
        referenceLocale: String,
        availableLocales: List<String>,
        referenceKeys: Set<String>
    ): List<String> {
        val orphaned = mutableSetOf<String>()
        val otherLocales = availableLocales.filter { it != referenceLocale }
        
        for (locale in otherLocales) {
            val allLocaleKeys = cacheService.getAllKeys()
            for (key in allLocaleKeys) {
                if (!referenceKeys.contains(key)) {
                    orphaned.add(key)
                }
            }
        }
        
        return orphaned.sorted()
    }

    /**
     * Generate an HTML report showing translation statistics.
     */
    fun generateHtmlReport(project: Project, config: ReportConfig = ReportConfig()): File? {
        val stats = generateStats(project, config)
        if (stats.totalKeys == 0) {
            thisLogger().warn("I18n Toolkit: No translations to report")
            return null
        }

        val projectPath = project.basePath ?: return null
        val outputDir = File(projectPath, config.outputDir)
        outputDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
        val reportFile = File(outputDir, "i18n-report-$timestamp.html")

        reportFile.writeText(buildHtmlReport(stats, project.name))
        thisLogger().info("I18n Toolkit: HTML report generated at ${reportFile.absolutePath}")
        
        return reportFile
    }

    /**
     * Generate a CSV report for spreadsheet import.
     */
    fun generateCsvReport(project: Project, config: ReportConfig = ReportConfig()): File? {
        val stats = generateStats(project, config)
        if (stats.totalKeys == 0) {
            thisLogger().warn("I18n Toolkit: No translations to report")
            return null
        }

        val projectPath = project.basePath ?: return null
        val outputDir = File(projectPath, config.outputDir)
        outputDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
        val reportFile = File(outputDir, "i18n-report-$timestamp.csv")

        reportFile.writeText(buildCsvReport(stats))
        thisLogger().info("I18n Toolkit: CSV report generated at ${reportFile.absolutePath}")
        
        return reportFile
    }

    private fun buildHtmlReport(stats: TranslationStats, projectName: String): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>i18n Translation Report - $projectName</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 40px; background: #f5f5f5; }
                .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }
                h2 { color: #555; margin-top: 30px; }
                .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 20px 0; }
                .card { background: #f8f9fa; padding: 20px; border-radius: 6px; border-left: 4px solid #4CAF50; }
                .card.warning { border-left-color: #ff9800; }
                .card.error { border-left-color: #f44336; }
                .card h3 { margin: 0 0 10px 0; color: #666; font-size: 14px; text-transform: uppercase; }
                .card .value { font-size: 32px; font-weight: bold; color: #333; }
                .card .detail { font-size: 14px; color: #888; margin-top: 5px; }
                table { width: 100%; border-collapse: collapse; margin-top: 20px; }
                th, td { text-align: left; padding: 12px; border-bottom: 1px solid #ddd; }
                th { background: #4CAF50; color: white; font-weight: 600; }
                tr:hover { background: #f5f5f5; }
                .progress-bar { height: 20px; background: #e0e0e0; border-radius: 10px; overflow: hidden; }
                .progress-fill { height: 100%; background: linear-gradient(90deg, #4CAF50, #8BC34A); transition: width 0.3s; }
                .progress-fill.low { background: linear-gradient(90deg, #f44336, #ff9800); }
                .progress-fill.medium { background: linear-gradient(90deg, #ff9800, #FFC107); }
                .badge { display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: 600; }
                .badge-success { background: #d4edda; color: #155724; }
                .badge-warning { background: #fff3cd; color: #856404; }
                .badge-error { background: #f8d7da; color: #721c24; }
                .missing-keys { max-height: 400px; overflow-y: auto; background: #f8f9fa; padding: 15px; border-radius: 4px; }
                .missing-keys ul { margin: 0; padding-left: 20px; }
                .missing-keys li { margin: 5px 0; font-family: monospace; font-size: 13px; }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🌐 i18n Translation Report</h1>
                <p style="color: #666;">Generated on ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}</p>
                
                <h2>📊 Summary</h2>
                <div class="summary">
                    <div class="card">
                        <h3>Total Keys</h3>
                        <div class="value">${stats.totalKeys}</div>
                    </div>
                    <div class="card">
                        <h3>Locales</h3>
                        <div class="value">${stats.localeStats.size}</div>
                    </div>
                    <div class="card ${if (stats.missingTranslations.isEmpty()) "" else "warning"}">
                        <h3>Missing Translations</h3>
                        <div class="value">${stats.missingTranslations.values.sumOf { it.size }}</div>
                        <div class="detail">across ${stats.missingTranslations.size} locales</div>
                    </div>
                    <div class="card ${if (stats.orphanedKeys.isEmpty()) "" else "warning"}">
                        <h3>Orphaned Keys</h3>
                        <div class="value">${stats.orphanedKeys.size}</div>
                    </div>
                </div>

                <h2>🌍 Locale Coverage</h2>
                <table>
                    <thead>
                        <tr>
                            <th>Locale</th>
                            <th>Translated Keys</th>
                            <th>Coverage</th>
                            <th>Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${stats.localeStats.values.joinToString("\n") { stat ->
                            val percentage = String.format("%.1f", stat.coveragePercent)
                            val statusClass = when {
                                stat.coveragePercent >= 100 -> "badge-success"
                                stat.coveragePercent >= 80 -> "badge-warning"
                                else -> "badge-error"
                            }
                            val statusText = when {
                                stat.coveragePercent >= 100 -> "Complete"
                                stat.coveragePercent >= 80 -> "Good"
                                stat.coveragePercent >= 50 -> "Needs Work"
                                else -> "Critical"
                            }
                            val progressClass = when {
                                stat.coveragePercent >= 80 -> ""
                                stat.coveragePercent >= 50 -> "medium"
                                else -> "low"
                            }
                            """
                            <tr>
                                <td><strong>${stat.locale}</strong></td>
                                <td>${stat.totalKeys} / ${stats.totalKeys}</td>
                                <td>
                                    <div class="progress-bar">
                                        <div class="progress-fill $progressClass" style="width: ${stat.coveragePercent}%"></div>
                                    </div>
                                    <div style="margin-top: 5px;">$percentage%</div>
                                </td>
                                <td><span class="badge $statusClass">$statusText</span></td>
                            </tr>
                            """
                        }}
                    </tbody>
                </table>

                ${if (stats.missingTranslations.isNotEmpty()) """
                <h2>⚠️ Missing Translations</h2>
                ${stats.missingTranslations.entries.joinToString("\n") { (locale, keys) ->
                    """
                    <h3>$locale (${keys.size} missing)</h3>
                    <div class="missing-keys">
                        <ul>
                            ${keys.joinToString("\n") { "<li>$it</li>" }}
                        </ul>
                    </div>
                    """
                }}
                """ else ""}

                ${if (stats.orphanedKeys.isNotEmpty()) """
                <h2>🗑️ Orphaned Keys</h2>
                <p>Keys present in non-reference locales but missing from the reference locale:</p>
                <div class="missing-keys">
                    <ul>
                        ${stats.orphanedKeys.joinToString("\n") { "<li>$it</li>" }}
                    </ul>
                </div>
                """ else ""}
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildCsvReport(stats: TranslationStats): String {
        val lines = mutableListOf<String>()
        
        // Header
        lines.add("Metric,Value")
        
        // Summary
        lines.add("Total Keys,${stats.totalKeys}")
        lines.add("Locales,${stats.localeStats.size}")
        lines.add("Missing Translations,${stats.missingTranslations.values.sumOf { it.size }}")
        lines.add("Orphaned Keys,${stats.orphanedKeys.size}")
        lines.add("")
        
        // Locale coverage
        lines.add("Locale Coverage")
        lines.add("Locale,Total Keys,Coverage %")
        stats.localeStats.values.forEach { stat ->
            lines.add("${stat.locale},${stat.totalKeys},${String.format("%.2f", stat.coveragePercent)}")
        }
        
        // Missing translations
        if (stats.missingTranslations.isNotEmpty()) {
            lines.add("")
            lines.add("Missing Translations")
            lines.add("Locale,Key")
            stats.missingTranslations.forEach { (locale, keys) ->
                keys.forEach { key ->
                    lines.add("$locale,$key")
                }
            }
        }
        
        return lines.joinToString("\n")
    }
}
