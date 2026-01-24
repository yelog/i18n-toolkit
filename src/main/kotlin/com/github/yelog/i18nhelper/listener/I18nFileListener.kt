package com.github.yelog.i18nhelper.listener

import com.github.yelog.i18nhelper.scanner.I18nDirectoryScanner
import com.github.yelog.i18nhelper.service.I18nCacheService
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
                        relevantEvents.forEach { event ->
                            event.file?.let { file ->
                                I18nCacheService.getInstance(project).invalidateFile(file)
                            }
                        }
                    }
                }
            }
        }
    }
}
