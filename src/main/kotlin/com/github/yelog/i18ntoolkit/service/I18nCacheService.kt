package com.github.yelog.i18ntoolkit.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.github.yelog.i18ntoolkit.detector.I18nFrameworkDetector
import com.github.yelog.i18ntoolkit.model.*
import com.github.yelog.i18ntoolkit.parser.TranslationFileParser
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.util.I18nKeyGenerator
import com.github.yelog.i18ntoolkit.util.I18nLocaleUtils
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher

@Service(Service.Level.PROJECT)
class I18nCacheService(private val project: Project) : Disposable {

    private val initialized = AtomicBoolean(false)
    private val cacheSnapshot = AtomicReference(CacheSnapshot.EMPTY)

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        refresh()
    }

    fun refresh() {
        if (project.isDisposed) return
        if (ApplicationManager.getApplication().isDispatchThread) {
            scheduleRefresh()
        } else {
            refreshBlocking()
        }
    }

    private fun refreshBlocking() {
        val basePath = project.basePath ?: return
        thisLogger().debug("I18n Toolkit: Refreshing translation cache for ${project.name} (blocking)")
        val snapshot = ReadAction.compute<CacheSnapshot, RuntimeException> {
            buildSnapshot(basePath)
        }
        publishSnapshot(snapshot)
    }

    private fun scheduleRefresh() {
        val basePath = project.basePath ?: return
        thisLogger().debug("I18n Toolkit: Scheduling translation cache refresh for ${project.name}")
        ReadAction.nonBlocking<CacheSnapshot> {
            buildSnapshot(basePath)
        }
            .expireWith(this)
            .coalesceBy(this, REFRESH_COALESCE_KEY)
            .finishOnUiThread(ModalityState.any()) { snapshot ->
                publishSnapshot(snapshot)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun buildSnapshot(basePath: String): CacheSnapshot {
        if (project.isDisposed) return CacheSnapshot.EMPTY

        val translationFiles = I18nDirectoryScanner.scanForTranslationFiles(project)
        val framework = I18nFrameworkDetector.detect(project)
        val data = TranslationData(framework)
        val keyIndex = mutableMapOf<String, MutableSet<TranslationEntry>>()

        translationFiles.forEach { file ->
            ProgressManager.checkCanceled()
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

                if (entries.isEmpty()) return@forEach

                entries.forEach { (key, entry) ->
                    translationFile.entries[key] = entry
                    data.addEntry(entry)
                    keyIndex.getOrPut(key) { linkedSetOf() }.add(entry)
                }

                data.files.add(translationFile)
            } catch (e: Exception) {
                thisLogger().warn("I18n Toolkit: Failed to parse ${file.path}", e)
            }
        }

        val immutableKeyIndex = keyIndex.mapValues { (_, entries) -> entries.toSet() }
        return CacheSnapshot(data, immutableKeyIndex)
    }

    private fun publishSnapshot(snapshot: CacheSnapshot) {
        cacheSnapshot.set(snapshot)
        thisLogger().debug("I18n Toolkit: Cached ${snapshot.translationData.translations.size} translation keys")
    }

    fun getTranslation(key: String, locale: String? = null): TranslationEntry? {
        val data = cacheSnapshot.get().translationData
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
        val data = cacheSnapshot.get().translationData
        val candidates = I18nLocaleUtils.buildLocaleCandidates(locale)
        for (candidate in candidates) {
            data.getTranslation(key, candidate)?.let { return it }
        }
        return null
    }

    fun getAllTranslations(key: String): Map<String, TranslationEntry> {
        return cacheSnapshot.get().translationData.getAllTranslations(key).toMap()
    }

    fun getAllKeys(): Set<String> {
        return cacheSnapshot.get().translationData.translations.keys.toSet()
    }

    fun getAvailableLocales(): List<String> {
        val locales = cacheSnapshot.get().translationData.files
            .map { it.locale }
            .filter { I18nLocaleUtils.isLocaleName(it) }

        return locales
            .groupBy { I18nLocaleUtils.normalizeLocale(it) }
            .values
            .map { I18nLocaleUtils.chooseDisplayLocale(it) }
            .filter { it.isNotBlank() }
            .sorted()
    }

    fun getEntriesForKey(key: String): Set<TranslationEntry> {
        return cacheSnapshot.get().keyToEntries[key] ?: emptySet()
    }

    fun findKeysByPrefix(prefix: String): List<String> {
        return getAllKeys().filter { it.startsWith(prefix) }
    }

    fun getTranslationFiles(): List<TranslationFile> {
        return cacheSnapshot.get().translationData.files.toList()
    }

    fun getFramework(): I18nFramework {
        return cacheSnapshot.get().translationData.framework
    }

    fun isTranslationFile(file: VirtualFile): Boolean {
        return cacheSnapshot.get().translationData.files.any { it.file == file }
    }

    fun getTranslationFile(file: VirtualFile): TranslationFile? {
        return cacheSnapshot.get().translationData.files.find { it.file == file }
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
        cacheSnapshot.set(CacheSnapshot.EMPTY)
        initialized.set(false)
    }

    private data class CacheSnapshot(
        val translationData: TranslationData,
        val keyToEntries: Map<String, Set<TranslationEntry>>
    ) {
        companion object {
            val EMPTY = CacheSnapshot(TranslationData(I18nFramework.UNKNOWN), emptyMap())
        }
    }

    companion object {
        private const val REFRESH_COALESCE_KEY = "i18n.cache.refresh"

        fun getInstance(project: Project): I18nCacheService {
            return project.getService(I18nCacheService::class.java)
        }
    }
}
