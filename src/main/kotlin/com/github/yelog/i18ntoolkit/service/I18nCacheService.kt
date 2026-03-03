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
import com.github.yelog.i18ntoolkit.util.I18nModuleResolver
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
        val moduleDataMap = mutableMapOf<String, TranslationData>()

        translationFiles.forEach { file ->
            ProgressManager.checkCanceled()
            try {
                val springLocale = I18nLocaleUtils.extractLocaleFromSpringFilename(file.nameWithoutExtension)
                val isSpringMessageLikeProperties = file.extension.equals("properties", ignoreCase = true) &&
                        file.nameWithoutExtension.startsWith("messages", ignoreCase = true)

                // In Spring projects, parse only locale-suffixed message bundles.
                if (framework == I18nFramework.SPRING_MESSAGE &&
                    isSpringMessageLikeProperties &&
                    springLocale == null
                ) {
                    return@forEach
                }

                val pathInfo = if (springLocale != null) {
                    // For Spring locale message files, locale comes from filename suffix.
                    I18nKeyGenerator.PathInfo(
                        locale = springLocale,
                        module = null,
                        businessUnit = null,
                        keyPrefix = ""
                    )
                } else {
                    I18nKeyGenerator.parseFilePath(file, basePath)
                }

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

                // Resolve the IntelliJ module for this file
                val moduleName = I18nModuleResolver.getModuleName(project, file)

                entries.forEach { (key, entry) ->
                    val entryWithModule = if (moduleName != null) entry.copy(moduleName = moduleName) else entry
                    translationFile.entries[key] = entryWithModule
                    data.addEntry(entryWithModule)
                    keyIndex.getOrPut(key) { linkedSetOf() }.add(entryWithModule)

                    // Also index by module
                    if (moduleName != null) {
                        val moduleData = moduleDataMap.getOrPut(moduleName) { TranslationData(framework) }
                        moduleData.addEntry(entryWithModule)
                    }
                }

                data.files.add(translationFile)
                if (moduleName != null) {
                    moduleDataMap.getOrPut(moduleName) { TranslationData(framework) }.files.add(translationFile)
                }
            } catch (e: Exception) {
                thisLogger().warn("I18n Toolkit: Failed to parse ${file.path}", e)
            }
        }

        val immutableKeyIndex = keyIndex.mapValues { (_, entries) -> entries.toSet() }
        val immutableModuleTranslations = moduleDataMap.toMap()
        return CacheSnapshot(data, immutableKeyIndex, immutableModuleTranslations)
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

    /**
     * Incrementally update cache for a specific file.
     * Only updates entries related to this file, keeping other cache data intact.
     * Much faster than full refresh for large projects.
     */
    fun invalidateFileIncremental(file: VirtualFile) {
        if (!I18nDirectoryScanner.isTranslationFile(file)) return

        val basePath = project.basePath ?: return

        // Use non-blocking read action for file parsing
        ReadAction.nonBlocking<Unit> {
            doIncrementalUpdate(file, basePath)
        }
            .expireWith(this)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun doIncrementalUpdate(file: VirtualFile, basePath: String) {
        if (project.isDisposed) return

        try {
            val currentSnapshot = cacheSnapshot.get()
            val framework = currentSnapshot.translationData.framework

            // Determine locale and key prefix for this file
            val springLocale = I18nLocaleUtils.extractLocaleFromSpringFilename(file.nameWithoutExtension)
            val isSpringMessageLikeProperties = file.extension.equals("properties", ignoreCase = true) &&
                    file.nameWithoutExtension.startsWith("messages", ignoreCase = true)

            // Skip Spring base properties files
            if (framework == I18nFramework.SPRING_MESSAGE &&
                isSpringMessageLikeProperties &&
                springLocale == null
            ) {
                return
            }

            val pathInfo = if (springLocale != null) {
                I18nKeyGenerator.PathInfo(
                    locale = springLocale,
                    module = null,
                    businessUnit = null,
                    keyPrefix = ""
                )
            } else {
                I18nKeyGenerator.parseFilePath(file, basePath)
            }

            val translationFile = TranslationFile(
                file = file,
                locale = pathInfo.locale,
                module = pathInfo.module,
                businessUnit = pathInfo.businessUnit,
                keyPrefix = pathInfo.keyPrefix
            )

            // Parse the updated file
            val entries = TranslationFileParser.parse(
                project,
                file,
                pathInfo.keyPrefix,
                pathInfo.locale
            )

            if (entries.isEmpty() && !currentSnapshot.translationData.files.any { it.file == file }) {
                // File has no entries and wasn't in cache before, nothing to do
                return
            }

            // Build new cache snapshot with incremental update
            val newSnapshot = buildIncrementalSnapshot(currentSnapshot, translationFile, entries, framework)

            // CAS update
            if (cacheSnapshot.compareAndSet(currentSnapshot, newSnapshot)) {
                thisLogger().debug("I18n Toolkit: Incremental cache update completed for ${file.name}")
                // Trigger UI refresh on EDT
                I18nUiRefresher.refresh(project)
            } else {
                // Another thread updated the cache, fall back to full refresh
                thisLogger().debug("I18n Toolkit: CAS failed for incremental update, falling back to full refresh")
                refresh()
            }
        } catch (e: Exception) {
            thisLogger().warn("I18n Toolkit: Incremental cache update failed for ${file.path}, falling back to full refresh", e)
            refresh()
        }
    }

    private fun buildIncrementalSnapshot(
        currentSnapshot: CacheSnapshot,
        updatedFile: TranslationFile,
        newEntries: Map<String, TranslationEntry>,
        framework: I18nFramework
    ): CacheSnapshot {
        // Create new mutable data structures based on current snapshot
        val newTranslations = currentSnapshot.translationData.translations.mapValues { (_, localeMap) ->
            localeMap.toMutableMap()
        }.toMutableMap()

        val newKeyToEntries = currentSnapshot.keyToEntries.mapValues { (_, entries) ->
            entries.toMutableSet()
        }.toMutableMap()

        val newFiles = currentSnapshot.translationData.files.filter { it.file != updatedFile.file }.toMutableList()
        val newModuleTranslations = currentSnapshot.moduleTranslations.mapValues { (_, data) ->
            TranslationData(
                framework = data.framework,
                files = data.files.filter { it.file != updatedFile.file }.toMutableList(),
                translations = data.translations.mapValues { (_, localeMap) ->
                    localeMap.toMutableMap()
                }.toMutableMap()
            )
        }.toMutableMap()

        // Remove old entries for this file
        val oldFile = currentSnapshot.translationData.files.find { it.file == updatedFile.file }
        if (oldFile != null) {
            oldFile.entries.forEach { (key, entry) ->
                newTranslations[key]?.remove(entry.locale)
                if (newTranslations[key]?.isEmpty() == true) {
                    newTranslations.remove(key)
                }
                newKeyToEntries[key]?.remove(entry)
                if (newKeyToEntries[key]?.isEmpty() == true) {
                    newKeyToEntries.remove(key)
                }
            }
        }

        // Add new entries
        val moduleName = I18nModuleResolver.getModuleName(project, updatedFile.file)
        val newTranslationFile = TranslationFile(
            file = updatedFile.file,
            locale = updatedFile.locale,
            module = updatedFile.module,
            businessUnit = updatedFile.businessUnit,
            keyPrefix = updatedFile.keyPrefix
        )

        newEntries.forEach { (key, entry) ->
            val entryWithModule = if (moduleName != null) entry.copy(moduleName = moduleName) else entry
            newTranslationFile.entries[key] = entryWithModule

            newTranslations.getOrPut(key) { mutableMapOf() }[entry.locale] = entryWithModule
            newKeyToEntries.getOrPut(key) { mutableSetOf() }.add(entryWithModule)

            // Update module-specific data
            if (moduleName != null) {
                val moduleData = newModuleTranslations.getOrPut(moduleName) {
                    TranslationData(framework)
                }
                moduleData.addEntry(entryWithModule)
            }
        }

        newFiles.add(newTranslationFile)
        if (moduleName != null) {
            newModuleTranslations[moduleName]?.files?.add(newTranslationFile)
        }

        // Create immutable copies
        val immutableKeyIndex = newKeyToEntries.mapValues { (_, entries) -> entries.toSet() }
        val immutableModuleTranslations = newModuleTranslations.toMap()
        val newTranslationData = TranslationData(
            framework = framework,
            files = newFiles,
            translations = newTranslations
        )

        return CacheSnapshot(newTranslationData, immutableKeyIndex, immutableModuleTranslations)
    }

    fun getOtherLocaleFiles(file: VirtualFile, key: String): List<TranslationEntry> {
        val currentLocale = getTranslationFile(file)?.locale
        return getAllTranslations(key)
            .filter { it.key != currentLocale }
            .map { it.value }
    }

    // --- Module-scoped queries for Spring Cloud microservice projects ---

    /**
     * Get translation for a key scoped to the module of the given file.
     * Falls back to dependency modules, then global.
     */
    fun getTranslationForModule(key: String, file: VirtualFile, locale: String? = null): TranslationEntry? {
        val moduleName = I18nModuleResolver.getModuleName(project, file)
        if (moduleName != null) {
            // 1. Try current module
            val moduleData = cacheSnapshot.get().moduleTranslations[moduleName]
            if (moduleData != null) {
                val entry = moduleData.getTranslation(key, locale)
                if (entry != null) return entry
            }

            // 2. Try dependency modules
            val depModules = I18nModuleResolver.getDependencyModuleNames(project, moduleName)
            for (depModule in depModules) {
                val depData = cacheSnapshot.get().moduleTranslations[depModule]
                if (depData != null) {
                    val entry = depData.getTranslation(key, locale)
                    if (entry != null) return entry
                }
            }
        }

        // 3. Fallback to global
        return getTranslation(key, locale)
    }

    /**
     * Get all translations for a key scoped to the module of the given file.
     */
    fun getAllTranslationsForModule(key: String, file: VirtualFile): Map<String, TranslationEntry> {
        val moduleName = I18nModuleResolver.getModuleName(project, file)
        val result = mutableMapOf<String, TranslationEntry>()

        if (moduleName != null) {
            // Collect from current module
            cacheSnapshot.get().moduleTranslations[moduleName]?.getAllTranslations(key)?.let {
                result.putAll(it)
            }

            // Collect from dependency modules
            val depModules = I18nModuleResolver.getDependencyModuleNames(project, moduleName)
            for (depModule in depModules) {
                cacheSnapshot.get().moduleTranslations[depModule]?.getAllTranslations(key)?.let { depEntries ->
                    depEntries.forEach { (locale, entry) ->
                        result.putIfAbsent(locale, entry)
                    }
                }
            }
        }

        // If nothing found in module scope, fallback to global
        if (result.isEmpty()) {
            return getAllTranslations(key)
        }
        return result
    }

    /**
     * Get all keys visible to the module of the given file.
     */
    fun getAllKeysForModule(file: VirtualFile): Set<String> {
        val moduleName = I18nModuleResolver.getModuleName(project, file)
        if (moduleName == null) return getAllKeys()

        val keys = mutableSetOf<String>()

        // Keys from current module
        cacheSnapshot.get().moduleTranslations[moduleName]?.translations?.keys?.let {
            keys.addAll(it)
        }

        // Keys from dependency modules
        val depModules = I18nModuleResolver.getDependencyModuleNames(project, moduleName)
        for (depModule in depModules) {
            cacheSnapshot.get().moduleTranslations[depModule]?.translations?.keys?.let {
                keys.addAll(it)
            }
        }

        // If no module-scoped keys found, fallback to global
        if (keys.isEmpty()) return getAllKeys()
        return keys
    }

    override fun dispose() {
        cacheSnapshot.set(CacheSnapshot.EMPTY)
        initialized.set(false)
    }

    private data class CacheSnapshot(
        val translationData: TranslationData,
        val keyToEntries: Map<String, Set<TranslationEntry>>,
        val moduleTranslations: Map<String, TranslationData> = emptyMap()
    ) {
        companion object {
            val EMPTY = CacheSnapshot(TranslationData(I18nFramework.UNKNOWN), emptyMap(), emptyMap())
        }
    }

    companion object {
        private const val REFRESH_COALESCE_KEY = "i18n.cache.refresh"

        fun getInstance(project: Project): I18nCacheService {
            return project.getService(I18nCacheService::class.java)
        }
    }
}
