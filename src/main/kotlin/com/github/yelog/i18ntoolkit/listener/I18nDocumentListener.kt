package com.github.yelog.i18ntoolkit.listener

import com.github.yelog.i18ntoolkit.hint.I18nInlayHintsProvider
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nDisplayMode
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nFunctionResolver
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
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

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val editor = event.editor
                if (editor.project != project || editor.isDisposed || project.isDisposed) return

                val settings = I18nSettingsState.getInstance(project)
                if (settings.state.displayMode != I18nDisplayMode.TRANSLATION_ONLY) return

                collapseI18nFoldsOutsideCaret(project, editor)
            }
        }

        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, project)

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

    private fun collapseI18nFoldsOutsideCaret(
        project: com.intellij.openapi.project.Project,
        editor: Editor
    ) {
        val caretOffset = editor.caretModel.offset
        val candidates = editor.foldingModel.allFoldRegions.filter { region ->
            region.isValid && region.isExpanded && !containsOffset(region, caretOffset)
        }
        if (candidates.isEmpty()) return

        val regionsToCollapse = ReadAction.compute<List<FoldRegion>, RuntimeException> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@compute emptyList()
            val i18nFunctions = I18nFunctionResolver.getFunctions(project)
            candidates.filter { region ->
                isI18nTranslationRegion(region, psiFile, i18nFunctions)
            }
        }
        if (regionsToCollapse.isEmpty()) return

        editor.foldingModel.runBatchFoldingOperation {
            regionsToCollapse.forEach { region ->
                if (region.isValid && region.isExpanded && !containsOffset(region, caretOffset)) {
                    region.isExpanded = false
                }
            }
        }
    }

    private fun isI18nTranslationRegion(
        region: FoldRegion,
        psiFile: com.intellij.psi.PsiFile,
        i18nFunctions: Set<String>
    ): Boolean {
        val element = psiFile.findElementAt(region.startOffset) ?: return false
        val literal = PsiTreeUtil.getParentOfType(element, JSLiteralExpression::class.java, false) ?: return false
        if (literal.textRange.startOffset != region.startOffset || literal.textRange.endOffset != region.endOffset) {
            return false
        }

        val callExpression = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return false
        val methodExpr = callExpression.methodExpression as? JSReferenceExpression ?: return false
        val methodName = methodExpr.referenceName ?: return false
        if (!i18nFunctions.contains(methodName)) return false

        val args = callExpression.arguments
        return args.isNotEmpty() && args[0] == literal
    }

    private fun containsOffset(region: FoldRegion, offset: Int): Boolean {
        return offset >= region.startOffset && offset <= region.endOffset
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
