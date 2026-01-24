package com.github.yelog.i18nhelper.hint

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nDisplayMode
import com.github.yelog.i18nhelper.settings.I18nSettingsState
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
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class I18nInlayHintsProvider : InlayHintsProvider<NoSettings> {

    companion object {
        private val fileProcessedOffsets = ConcurrentHashMap<String, MutableSet<Int>>()
        private val fileTimestamps = ConcurrentHashMap<String, Long>()

        fun getOrCreateOffsetSet(filePath: String, documentTimestamp: Long): MutableSet<Int> {
            val lastTimestamp = fileTimestamps[filePath]
            if (lastTimestamp != documentTimestamp) {
                fileProcessedOffsets[filePath] = ConcurrentHashMap.newKeySet()
                fileTimestamps[filePath] = documentTimestamp
            }
            return fileProcessedOffsets.getOrPut(filePath) { ConcurrentHashMap.newKeySet() }
        }

        fun clearCache() {
            fileProcessedOffsets.clear()
            fileTimestamps.clear()
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
        val filePath = file.originalFile.virtualFile?.path
            ?: file.virtualFile?.path
            ?: file.name
        val timestamp = editor.document.modificationStamp
        return I18nInlayHintsCollector(editor, filePath, timestamp)
    }

    private class I18nInlayHintsCollector(
        editor: Editor,
        private val filePath: String,
        private val documentTimestamp: Long
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

            val key = when (firstArg) {
                is JSLiteralExpression -> firstArg.stringValue
                else -> null
            } ?: return true

            val project = element.project
            val cacheService = I18nCacheService.getInstance(project)
            val settings = I18nSettingsState.getInstance(project)
            if (settings.state.displayMode == I18nDisplayMode.TRANSLATION_ONLY) return true

            val locale = settings.getDisplayLocaleOrNull()
            val translation = cacheService.getTranslation(key, locale) ?: return true

            val processedOffsets = getOrCreateOffsetSet(filePath, documentTimestamp)
            if (!processedOffsets.add(offset)) return true

            val translationText = truncateText(translation.value, 50)
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
