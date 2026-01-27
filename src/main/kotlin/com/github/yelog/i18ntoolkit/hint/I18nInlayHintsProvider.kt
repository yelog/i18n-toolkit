package com.github.yelog.i18ntoolkit.hint

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nDisplayMode
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
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
        return I18nInlayHintsCollector(editor, filePath, modStamp)
    }

    private class I18nInlayHintsCollector(
        editor: Editor,
        private val filePath: String,
        private val modStamp: Long
    ) : FactoryInlayHintsCollector(editor) {

        private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (element !is JSCallExpression) return true

            val methodExpr = element.methodExpression as? JSReferenceExpression ?: return true
            val methodName = methodExpr.referenceName ?: return true

            if (!i18nFunctions.contains(methodName)) return true

            val args = element.arguments
            if (args.isEmpty()) return true

            val firstArg = args[0]
            val offset = getHostOffset(firstArg, firstArg.textRange.endOffset)

            // Deduplicate across all language collector instances using global cache
            // Key includes modification stamp so hints are refreshed when file changes
            val hintKey = "$filePath:$modStamp:$offset"
            checkAndCleanCache()
            if (globalProcessedHints.putIfAbsent(hintKey, true) != null) return true

            val partialKey = when (firstArg) {
                is JSLiteralExpression -> firstArg.stringValue
                else -> null
            } ?: return true

            // Resolve full key including namespace from useTranslation hook
            val fullKey = I18nNamespaceResolver.getFullKey(element, partialKey)

            val project = element.project
            val cacheService = I18nCacheService.getInstance(project)
            val settings = I18nSettingsState.getInstance(project)
            if (settings.state.displayMode == I18nDisplayMode.TRANSLATION_ONLY) return true

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
                        return true // Key doesn't exist at all, don't show hint
                    }
                }
            } else {
                // No display locale set, use any available translation
                val translation = cacheService.getTranslation(fullKey, null)
                    ?: cacheService.getTranslation(partialKey, null)
                    ?: return true
                truncateText(translation.value, 50)
            }

            val presentation = createPresentation(factory, translationText)

            sink.addInlineElement(
                offset,
                true,
                presentation,
                false
            )

            return true
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
