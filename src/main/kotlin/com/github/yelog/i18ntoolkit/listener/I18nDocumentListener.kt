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
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
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
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.project.Project
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

/**
 * Listens for in-memory document edits on translation files and triggers
 * a debounced cache + UI refresh. This complements I18nFileListener which
 * only fires on VFS (disk-level) changes.
 */
class I18nDocumentListenerActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        I18nDocumentListenerRegistrar.getInstance(project).initialize()
    }
}

@Service(Service.Level.PROJECT)
class I18nDocumentListenerRegistrar(private val project: Project) : Disposable {
    private val attachedDocuments = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Document, Boolean>())
    )
    private val debounceTimer = AtomicReference<Timer?>(null)

    fun initialize() {
        val docListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (!I18nDirectoryScanner.isTranslationFile(file)) return

                // Cancel previous timer and schedule new one
                debounceTimer.get()?.stop()
                val timer = Timer(500) {
                    if (!project.isDisposed) {
                        // Use incremental update for cache (immediate, lightweight)
                        I18nCacheService.getInstance(project).invalidateFileIncremental(file)
                        // Use delayed refresh for UI (background silent, avoids flickering)
                        I18nUiRefresher.refreshDelayed(project, file)
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

        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, this)

        val editorFactoryListener = object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val document = event.editor.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return

                // Clear inlay hints cache for this specific file when it's opened
                // This fixes the issue where hints don't show when a file is reopened
                I18nInlayHintsProvider.clearCacheForFile(file.path)

                if (I18nDirectoryScanner.isTranslationFile(file)) {
                    attachListenerToDocument(document, docListener)
                }
            }
        }

        // Register for future editors
        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, this)

        // Process already-open editors
        for (editor in EditorFactory.getInstance().allEditors) {
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: continue

            if (I18nDirectoryScanner.isTranslationFile(file)) {
                attachListenerToDocument(document, docListener)
            }
        }

        // Trigger UI refresh for already-open files
        // This clears the inlay hints cache and reparses all open files
        // Fixes the issue where hints don't show when IDEA starts with files already open
        I18nUiRefresher.refresh(project)

    }

    override fun dispose() {
        debounceTimer.getAndSet(null)?.stop()
        attachedDocuments.clear()
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
        listener: DocumentListener
    ) {
        if (!attachedDocuments.add(document)) return
        document.addDocumentListener(listener, this)
    }

    companion object {
        fun getInstance(project: Project): I18nDocumentListenerRegistrar {
            return project.getService(I18nDocumentListenerRegistrar::class.java)
        }
    }
}
