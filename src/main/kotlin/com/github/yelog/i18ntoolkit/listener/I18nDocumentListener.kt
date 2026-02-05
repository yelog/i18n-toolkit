package com.github.yelog.i18ntoolkit.listener

import com.github.yelog.i18ntoolkit.hint.I18nInlayHintsProvider
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

/**
 * Listens for in-memory document edits on translation files and triggers
 * a debounced cache + UI refresh. This complements I18nFileListener which
 * only fires on VFS (disk-level) changes.
 */
class I18nDocumentListenerRegistrar : ProjectActivity {

    companion object {
        // User data key to mark documents that already have our listener attached
        private val LISTENER_ATTACHED_KEY = Key.create<Boolean>("I18nDocumentListenerAttached")
    }

    override suspend fun execute(project: com.intellij.openapi.project.Project) {
        val debounceTimer = AtomicReference<Timer?>(null)

        val docListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (!I18nDirectoryScanner.isTranslationFile(file)) return

                // Debounce: reset timer on each change, fire after 500ms of inactivity
                debounceTimer.get()?.stop()
                val timer = Timer(500) {
                    if (!project.isDisposed) {
                        I18nCacheService.getInstance(project).refresh()
                        I18nUiRefresher.refresh(project)
                    }
                }
                timer.isRepeats = false
                debounceTimer.set(timer)
                timer.start()
            }
        }

        val editorFactoryListener = object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val document = event.editor.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return

                // Clear inlay hints cache for this specific file when it's opened
                // This fixes the issue where hints don't show when a file is reopened
                I18nInlayHintsProvider.clearCacheForFile(file.path)

                if (I18nDirectoryScanner.isTranslationFile(file)) {
                    attachListenerToDocument(document, docListener, project)
                }
            }
        }

        // Register for future editors
        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, project)

        // Process already-open editors
        for (editor in EditorFactory.getInstance().allEditors) {
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: continue

            if (I18nDirectoryScanner.isTranslationFile(file)) {
                attachListenerToDocument(document, docListener, project)
            }
        }

        // Trigger UI refresh for already-open files
        // This clears the inlay hints cache and reparses all open files
        // Fixes the issue where hints don't show when IDEA starts with files already open
        I18nUiRefresher.refresh(project)

        // Clean up debounce timer on project dispose
        Disposer.register(project) {
            debounceTimer.get()?.stop()
        }
    }

    /**
     * Attach listener to document only if not already attached.
     * Uses user data to track whether the listener has been added.
     */
    private fun attachListenerToDocument(
        document: Document,
        listener: DocumentListener,
        project: com.intellij.openapi.project.Project
    ) {
        // Check if listener is already attached
        if (document.getUserData(LISTENER_ATTACHED_KEY) == true) {
            return
        }

        // Mark as attached and add the listener
        document.putUserData(LISTENER_ATTACHED_KEY, true)
        document.addDocumentListener(listener, project)
    }
}
