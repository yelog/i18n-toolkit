package com.github.yelog.i18nhelper.service

import com.github.yelog.i18nhelper.detector.I18nFrameworkDetector
import com.github.yelog.i18nhelper.model.*
import com.github.yelog.i18nhelper.parser.TranslationFileParser
import com.github.yelog.i18nhelper.scanner.I18nDirectoryScanner
import com.github.yelog.i18nhelper.util.I18nKeyGenerator
import com.github.yelog.i18nhelper.util.I18nLocaleUtils
import com.github.yelog.i18nhelper.util.I18nUiRefresher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class I18nCacheService(private val project: Project) : Disposable {

    private var translationData: TranslationData? = null
    private val keyToFiles = ConcurrentHashMap<String, MutableSet<TranslationEntry>>()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        refresh()
    }

    fun refresh() {
        thisLogger().info("I18n Helper: Refreshing translation cache for ${project.name}")

        val basePath = project.basePath ?: return
        val translationFiles = I18nDirectoryScanner.scanForTranslationFiles(project)

        // Clear old cache to prevent stale offsets
        keyToFiles.clear()

        // Use ReadAction for all PSI access operations
        val data = ReadAction.compute<TranslationData, RuntimeException> {
            val framework = I18nFrameworkDetector.detect(project)
            val result = TranslationData(framework)

            translationFiles.forEach { file ->
                try {
                    val pathInfo = I18nKeyGenerator.parseFilePath(file, basePath)

                    val translationFile = TranslationFile(
                        file = file,
                        locale = pathInfo.locale,
                        module = pathInfo.module,
                        businessUnit = pathInfo.businessUnit,
                        keyPrefix = pathInfo.keyPrefix
                    )

                    val entries = TranslationFileParser.parse(
                        project,
                        file,
                        pathInfo.keyPrefix,
                        pathInfo.locale
                    )

                    entries.forEach { (key, entry) ->
                        translationFile.entries[key] = entry
                        result.addEntry(entry)
                        keyToFiles.getOrPut(key) { mutableSetOf() }.add(entry)
                    }

                    result.files.add(translationFile)
                } catch (e: Exception) {
                    thisLogger().warn("I18n Helper: Failed to parse ${file.path}", e)
                }
            }

            result
        }

        translationData = data
        thisLogger().info("I18n Helper: Cached ${data.translations.size} translation keys")
    }

    fun getTranslation(key: String, locale: String? = null): TranslationEntry? {
        val data = translationData ?: return null
        if (locale == null) return data.getTranslation(key, null)

        val candidates = I18nLocaleUtils.buildLocaleCandidates(locale)
        for (candidate in candidates) {
            data.getTranslation(key, candidate)?.let { return it }
        }

        return data.getTranslation(key, null)
    }

    /**
     * Get translation for a specific locale without fallback to other locales.
     * Returns null if the exact locale is not found.
     */
    fun getTranslationStrict(key: String, locale: String): TranslationEntry? {
        val data = translationData ?: return null
        val candidates = I18nLocaleUtils.buildLocaleCandidates(locale)
        for (candidate in candidates) {
            data.getTranslation(key, candidate)?.let { return it }
        }
        return null
    }

    fun getAllTranslations(key: String): Map<String, TranslationEntry> {
        return translationData?.getAllTranslations(key) ?: emptyMap()
    }

    fun getAllKeys(): Set<String> {
        return translationData?.translations?.keys ?: emptySet()
    }

    fun getAvailableLocales(): List<String> {
        return translationData?.files
            ?.map { it.locale }
            ?.filter { I18nLocaleUtils.isLocaleName(it) }
            ?.map { I18nLocaleUtils.normalizeLocale(it) }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }

    fun getEntriesForKey(key: String): Set<TranslationEntry> {
        return keyToFiles[key] ?: emptySet()
    }

    fun findKeysByPrefix(prefix: String): List<String> {
        return getAllKeys().filter { it.startsWith(prefix) }
    }

    fun getTranslationFiles(): List<TranslationFile> {
        return translationData?.files ?: emptyList()
    }

    fun getFramework(): I18nFramework {
        return translationData?.framework ?: I18nFramework.UNKNOWN
    }

    fun isTranslationFile(file: VirtualFile): Boolean {
        return translationData?.files?.any { it.file == file } ?: false
    }

    fun getTranslationFile(file: VirtualFile): TranslationFile? {
        return translationData?.files?.find { it.file == file }
    }

    fun invalidateFile(file: VirtualFile) {
        if (I18nDirectoryScanner.isTranslationFile(file)) {
            refresh()
            I18nUiRefresher.refresh(project)
        }
    }

    fun getOtherLocaleFiles(file: VirtualFile, key: String): List<TranslationEntry> {
        val currentLocale = getTranslationFile(file)?.locale
        return getAllTranslations(key)
            .filter { it.key != currentLocale }
            .map { it.value }
    }

    override fun dispose() {
        translationData = null
        keyToFiles.clear()
    }

    companion object {
        fun getInstance(project: Project): I18nCacheService {
            return project.getService(I18nCacheService::class.java)
        }
    }
}
