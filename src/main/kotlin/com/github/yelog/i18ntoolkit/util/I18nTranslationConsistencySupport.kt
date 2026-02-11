package com.github.yelog.i18ntoolkit.util

import com.github.yelog.i18ntoolkit.model.TranslationFile
import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.intellij.openapi.project.Project

object I18nTranslationConsistencySupport {

    data class MissingTranslationTarget(
        val locale: String,
        val translationFile: TranslationFile
    )

    fun collectMissingTargets(
        cacheService: I18nCacheService,
        key: String,
        allLocales: List<String>
    ): List<MissingTranslationTarget> {
        val existingLocales = cacheService.getAllTranslations(key).keys
        val targetFiles = I18nKeyCreationSupport.findTargetFiles(cacheService, key)
            .groupBy { it.locale }

        return allLocales
            .asSequence()
            .filter { it !in existingLocales }
            .mapNotNull { locale ->
                val file = targetFiles[locale]
                    ?.sortedBy { it.file.path }
                    ?.firstOrNull()
                    ?: return@mapNotNull null
                MissingTranslationTarget(locale, file)
            }
            .toList()
    }

    fun createMissingTranslation(
        project: Project,
        key: String,
        locale: String,
        initialValue: String = ""
    ): TranslationEntry? {
        val cacheService = I18nCacheService.getInstance(project)
        val existing = cacheService.getAllTranslations(key)[locale]
        if (existing != null) return existing

        val target = collectMissingTargets(cacheService, key, listOf(locale)).firstOrNull() ?: return null
        val created = I18nKeyCreationSupport.createKeyInTranslationFile(
            project = project,
            translationFile = target.translationFile,
            fullKey = key,
            initialValue = initialValue
        )
        if (created == null) return null

        cacheService.refresh()
        return cacheService.getAllTranslations(key)[locale]
    }

    fun fillMissingTranslations(
        project: Project,
        key: String,
        allLocales: List<String>,
        initialValueProvider: (String) -> String = { "" }
    ): Int {
        val cacheService = I18nCacheService.getInstance(project)
        val targets = collectMissingTargets(cacheService, key, allLocales)
        if (targets.isEmpty()) return 0

        var createdCount = 0
        for (target in targets) {
            val created = I18nKeyCreationSupport.createKeyInTranslationFile(
                project = project,
                translationFile = target.translationFile,
                fullKey = key,
                initialValue = initialValueProvider(target.locale)
            )
            if (created != null) createdCount++
        }

        if (createdCount > 0) {
            cacheService.refresh()
        }
        return createdCount
    }
}
