package com.github.yelog.i18ntoolkit.listener

import com.github.yelog.i18ntoolkit.hint.I18nInlayHintsProvider
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.psi.PsiManager

/**
 * Listens for application activation events and clears inlay hints cache.
 * This fixes the issue where hints disappear after switching away from IDEA for a long time.
 *
 * When IDEA is in the background for a while, it may clear inlay hints to save memory.
 * Upon reactivation, IntelliJ will try to re-render hints, but our global cache prevents it.
 * By clearing the cache on activation, we allow hints to be re-rendered naturally.
 */
class I18nApplicationActivationListener : ApplicationActivationListener {

    companion object {
        private var lastActivationTime = 0L
        // Only trigger refresh if IDEA was inactive for more than 30 seconds
        private const val MIN_INACTIVE_DURATION_MS = 30_000L
    }

    override fun applicationActivated(ideFrame: IdeFrame) {
        val project = ideFrame.project ?: return
        if (project.isDisposed) return

        val now = System.currentTimeMillis()
        val timeSinceLastActivation = now - lastActivationTime
        lastActivationTime = now

        // Only clear cache if app was inactive for a significant time
        // This avoids performance issues from frequent window switching
        if (timeSinceLastActivation < MIN_INACTIVE_DURATION_MS) {
            return
        }

        thisLogger().debug("I18n Toolkit: Application activated after ${timeSinceLastActivation}ms, clearing hints cache")

        // Clear inlay hints cache to ensure hints can be re-rendered
        // This fixes the issue where hints disappear after switching away from IDEA
        I18nInlayHintsProvider.clearCache()

        // Trigger a lightweight daemon analysis refresh for open files
        // This is more efficient than full file reparsing
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val psiManager = PsiManager.getInstance(project)

                fileEditorManager.openFiles.forEach { virtualFile ->
                    psiManager.findFile(virtualFile)?.let { psiFile ->
                        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                    }
                }
            }
        }
    }
}
