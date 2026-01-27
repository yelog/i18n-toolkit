package com.github.yelog.i18nhelper.listener

import com.github.yelog.i18nhelper.scanner.I18nDirectoryScanner
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nUiRefresher
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
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
                val file = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
                if (I18nDirectoryScanner.isTranslationFile(file)) {
                    event.editor.document.addDocumentListener(docListener, project)
                }
            }
        }

        // Register for future editors
        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, project)

        // Attach to already-open editors
        for (editor in EditorFactory.getInstance().allEditors) {
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: continue
            if (I18nDirectoryScanner.isTranslationFile(file)) {
                editor.document.addDocumentListener(docListener, project)
            }
        }

        // Clean up debounce timer on project dispose
        Disposer.register(project) {
            debounceTimer.get()?.stop()
        }
    }
}
