package com.github.yelog.i18ntoolkit.util

import com.github.yelog.i18ntoolkit.folding.I18nFoldingBuilder
import com.github.yelog.i18ntoolkit.hint.I18nInlayHintsProvider
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiManager
import com.intellij.util.FileContentUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.intellij.util.concurrency.AppExecutorUtil

object I18nUiRefresher {
    const val SWITCH_LOCALE_ACTION_ID = "I18nToolkit.SwitchLocale"
    const val TRANSLATIONS_POPUP_ACTION_ID = "I18nToolkit.ShowTranslationsPopup"
    const val COPY_KEY_ACTION_ID = "I18nToolkit.CopyKey"
    const val NAVIGATE_TO_FILE_ACTION_ID = "I18nToolkit.NavigateToFile"
    const val STATUS_BAR_WIDGET_ID = "I18nDisplayLanguage"

    // Default delay for background silent refresh (milliseconds)
    private const val DEFAULT_REFRESH_DELAY_MS = 1500L
    // Threshold for immediate refresh - files larger than this trigger immediate UI update
    private const val LARGE_FILE_THRESHOLD_BYTES = 100 * 1024L // 100KB

    // Per-project scheduled refresh tasks for cancellation
    private val pendingRefreshes = ConcurrentHashMap<Project, ScheduledFuture<*>>()

    /**
     * Immediate UI refresh - clears caches and reparses all open files.
     * Use this when immediate feedback is required (e.g., user action).
     */
    fun refresh(project: Project) {
        cancelPendingRefresh(project)
        performRefresh(project)
    }

    /**
     * Delayed UI refresh with smart behavior:
     * - Small file changes (<100KB): delay 1.5s to avoid UI flickering during typing
     * - Large file changes (>=100KB): refresh immediately as it's likely a bulk operation
     * - Multiple changes within delay window: only last one executes (debounced)
     *
     * @param project The project to refresh
     * @param changedFile Optional file that was changed (used to determine immediate vs delayed)
     * @param delayMs Delay in milliseconds (default 1500ms)
     */
    fun refreshDelayed(
        project: Project,
        changedFile: VirtualFile? = null,
        delayMs: Long = DEFAULT_REFRESH_DELAY_MS
    ) {
        if (project.isDisposed) return

        // Cancel any pending refresh for this project
        cancelPendingRefresh(project)

        // Determine if we should refresh immediately
        val shouldRefreshImmediately = when {
            changedFile == null -> false
            changedFile.length >= LARGE_FILE_THRESHOLD_BYTES -> true
            else -> false
        }

        if (shouldRefreshImmediately) {
            // Large file changes trigger immediate refresh
            performRefresh(project)
        } else {
            // Schedule delayed refresh for small changes
            val scheduledTask = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                pendingRefreshes.remove(project)
                if (!project.isDisposed) {
                    performRefresh(project)
                }
            }, delayMs, TimeUnit.MILLISECONDS)

            pendingRefreshes[project] = scheduledTask
        }
    }

    /**
     * Cancel any pending delayed refresh for the project.
     * Call this when an immediate refresh is triggered to avoid duplicate work.
     */
    fun cancelPendingRefresh(project: Project) {
        pendingRefreshes.remove(project)?.cancel(false)
    }

    /**
     * Check if there's a pending refresh for the project.
     */
    fun hasPendingRefresh(project: Project): Boolean {
        val task = pendingRefreshes[project]
        return task != null && !task.isDone && !task.isCancelled
    }

    private fun performRefresh(project: Project) {
        // Clear all caches
        I18nInlayHintsProvider.clearCache()
        I18nFoldingBuilder.clearCache()

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Get all open files
            val fileEditorManager = FileEditorManager.getInstance(project)
            val openFiles = fileEditorManager.openFiles.toList()

            if (openFiles.isNotEmpty()) {
                // Reparse files to force rebuild of inlay hints and folding regions
                // This will automatically trigger daemon analysis and refresh all annotations/hints
                FileContentUtil.reparseFiles(project, openFiles, true)
            }

            // Update status bar widget
            updateStatusBarWidget(project)
        }
    }

    private fun updateStatusBarWidget(project: Project) {
        val statusBar = WindowManager.getInstance().getStatusBar(project)
        statusBar?.updateWidget(STATUS_BAR_WIDGET_ID)
    }
}
