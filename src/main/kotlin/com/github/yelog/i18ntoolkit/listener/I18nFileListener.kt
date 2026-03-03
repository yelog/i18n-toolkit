package com.github.yelog.i18ntoolkit.listener

import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.*

class I18nFileListener : AsyncFileListener {

    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val relevantEvents = events.filter { event ->
            val file = event.file ?: return@filter false
            when (event) {
                is VFileContentChangeEvent,
                is VFileCreateEvent,
                is VFileDeleteEvent,
                is VFileMoveEvent,
                is VFileCopyEvent -> I18nDirectoryScanner.isTranslationFile(file)
                else -> false
            }
        }

        if (relevantEvents.isEmpty()) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                ProjectManager.getInstance().openProjects.forEach { project ->
                    if (!project.isDisposed) {
                        val cacheService = I18nCacheService.getInstance(project)
                        relevantEvents.forEach { event ->
                            event.file?.let { file ->
                                when (event) {
                                    is VFileContentChangeEvent -> {
                                        // Content change: use incremental update for better performance
                                        cacheService.invalidateFileIncremental(file)
                                    }
                                    is VFileCreateEvent -> {
                                        // New file: need full refresh to include it
                                        cacheService.invalidateFile(file)
                                    }
                                    is VFileDeleteEvent -> {
                                        // Deleted file: use incremental update to remove its entries
                                        cacheService.invalidateFileIncremental(file)
                                    }
                                    is VFileMoveEvent, is VFileCopyEvent -> {
                                        // Move/Copy: need full refresh
                                        cacheService.invalidateFile(file)
                                    }
                                    else -> cacheService.invalidateFile(file)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
