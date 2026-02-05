package com.github.yelog.i18ntoolkit.hint

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nDisplayMode
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class I18nInlayHintsProvider : InlayHintsProvider<NoSettings> {

    companion object {
        @Volatile
        private var settingsVersion = 0L

        // Global cache to track processed hints across language instances
        // Key: "filePath:modStamp:offset" - includes modification stamp for automatic invalidation
        private val globalProcessedHints = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private const val MAX_CACHE_SIZE = 10000

        fun clearCache() {
            // Increment version to invalidate any cached state
            settingsVersion++
            globalProcessedHints.clear()
        }

        /**
         * Clear cache entries for a specific file path.
         * This allows hints to be re-rendered when a file is reopened.
         */
        fun clearCacheForFile(filePath: String) {
            globalProcessedHints.keys.removeIf { key ->
                key.startsWith("$filePath:")
            }
        }

        private fun checkAndCleanCache() {
            // Prevent memory leak by clearing cache when it gets too large
            if (globalProcessedHints.size > MAX_CACHE_SIZE) {
                globalProcessedHints.clear()
            }
        }
    }

    override val key: SettingsKey<NoSettings> = SettingsKey("i18n.inlay.hints")
    override val name: String = "I18n Translation Hints"
    override val previewText: String = "t('hello.world')"

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        val filePath = file.virtualFile?.path ?: ""
        val modStamp = file.modificationStamp
        return I18nInlayHintsCollector(editor, filePath, modStamp, file)
    }

    // Override these methods to prevent Kotlin from generating super calls to interface default methods
    // that may not exist in older IDE versions (compatibility with 2023.1+)
    override fun getSettingsLanguage(language: Language): Language {
        // Return the provided language for settings
        return language
    }

    override fun createFile(project: Project, fileType: FileType, document: Document): PsiFile {
        // Create a file using JavaScript language for preview
        val language = Language.findLanguageByID("JavaScript") ?: Language.ANY
        return PsiFileFactory.getInstance(project)
            .createFileFromText("preview.js", language, document.text)
    }

    private class I18nInlayHintsCollector(
        editor: Editor,
        private val filePath: String,
        private val modStamp: Long,
        private val hostFile: PsiFile
    ) : FactoryInlayHintsCollector(editor) {

        private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")
        private var injectedFragmentsProcessed = false

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            // Process injected language fragments once (for Vue template interpolations like {{ t('key') }})
            if (!injectedFragmentsProcessed) {
                injectedFragmentsProcessed = true
                processInjectedFragments(editor, sink)
            }

            if (element !is JSCallExpression) return true

            processI18nCall(element, editor, sink)
            return true
        }

        private fun processInjectedFragments(editor: Editor, sink: InlayHintsSink) {
            val project = hostFile.project
            val injectedManager = InjectedLanguageManager.getInstance(project)

            // Process ALL JSCallExpression in the host file (handles script section and some embedded JS)
            val allCallsInHost = PsiTreeUtil.findChildrenOfType(hostFile, JSCallExpression::class.java)
            for (call in allCallsInHost) {
                processI18nCall(call, editor, sink)
            }

            // Process injected language fragments (for {{ }} interpolations in Vue templates)
            hostFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)
                    // Try to get injected files for any element that might be an injection host
                    try {
                        val injectedFiles = injectedManager.getInjectedPsiFiles(element)
                        injectedFiles?.forEach { pair ->
                            val injectedFile = pair.first
                            val calls = PsiTreeUtil.findChildrenOfType(injectedFile, JSCallExpression::class.java)
                            for (call in calls) {
                                processI18nCall(call, editor, sink)
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore elements that don't support injection
                    }

                    // Also try findInjectedElementAt for the element's position
                    val injectedElement = injectedManager.findInjectedElementAt(hostFile, element.textOffset)
                    if (injectedElement != null) {
                        val injectedCalls = PsiTreeUtil.findChildrenOfType(injectedElement.containingFile, JSCallExpression::class.java)
                        for (call in injectedCalls) {
                            processI18nCall(call, editor, sink)
                        }
                    }
                }
            })
        }

        private fun processI18nCall(element: JSCallExpression, editor: Editor, sink: InlayHintsSink) {
            val methodExpr = element.methodExpression as? JSReferenceExpression ?: return
            val methodName = methodExpr.referenceName ?: return

            if (!i18nFunctions.contains(methodName)) return

            val args = element.arguments
            if (args.isEmpty()) return

            val firstArg = args[0]
            val offset = getHostOffset(firstArg, firstArg.textRange.endOffset)

            // Deduplicate across all language collector instances using global cache
            // Key includes modification stamp so hints are refreshed when file changes
            val hintKey = "$filePath:$modStamp:$offset"
            checkAndCleanCache()
            if (globalProcessedHints.putIfAbsent(hintKey, true) != null) return

            val partialKey = when (firstArg) {
                is JSLiteralExpression -> firstArg.stringValue
                else -> null
            } ?: return

            // Resolve full key including namespace from useTranslation hook
            val fullKey = I18nNamespaceResolver.getFullKey(element, partialKey)

            val project = element.project
            val cacheService = I18nCacheService.getInstance(project)
            val settings = I18nSettingsState.getInstance(project)
            if (settings.state.displayMode == I18nDisplayMode.TRANSLATION_ONLY) return

            val locale = settings.getDisplayLocaleOrNull()

            // Determine the text to display
            val translationText = if (locale != null) {
                // Try full key first, then partial key as fallback
                val translation = cacheService.getTranslationStrict(fullKey, locale)
                    ?: cacheService.getTranslationStrict(partialKey, locale)

                if (translation != null) {
                    truncateText(translation.value, 50)
                } else {
                    // Check if the key exists in any locale
                    val hasAnyTranslation = cacheService.getAllTranslations(fullKey).isNotEmpty()
                        || cacheService.getAllTranslations(partialKey).isNotEmpty()
                    if (hasAnyTranslation) {
                        "⚠ Missing translation for '$locale'"
                    } else {
                        return // Key doesn't exist at all, don't show hint
                    }
                }
            } else {
                // No display locale set, use any available translation
                val translation = cacheService.getTranslation(fullKey, null)
                    ?: cacheService.getTranslation(partialKey, null)
                    ?: return
                truncateText(translation.value, 50)
            }

            val presentation = createPresentation(factory, translationText)

            sink.addInlineElement(
                offset,
                true,
                presentation,
                false
            )
        }

        private fun getHostOffset(element: PsiElement, injectedOffset: Int): Int {
            val file = element.containingFile
            val manager = InjectedLanguageManager.getInstance(element.project)
            return if (manager.isInjectedFragment(file)) {
                manager.injectedToHost(file, injectedOffset)
            } else {
                injectedOffset
            }
        }

        private fun createPresentation(factory: PresentationFactory, text: String): InlayPresentation {
            val textPresentation = factory.smallText(" → $text")
            return factory.roundWithBackground(textPresentation)
        }

        private fun truncateText(text: String, maxLength: Int): String {
            return if (text.length > maxLength) {
                text.take(maxLength - 3) + "..."
            } else {
                text
            }
        }
    }
}
