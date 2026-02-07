package com.github.yelog.i18ntoolkit.spring

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nDisplayMode
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class JavaI18nInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("i18n.java.inlay.hints")
    override val name: String = "I18n Translation Hints (Java)"
    override val previewText: String = "messageSource.getMessage(\"hello.world\", null, locale)"

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
        return JavaI18nInlayHintsCollector(editor, file)
    }

    override fun getSettingsLanguage(language: Language): Language = language

    override fun createFile(project: Project, fileType: FileType, document: Document): PsiFile {
        val language = Language.findLanguageByID("JAVA") ?: Language.ANY
        return PsiFileFactory.getInstance(project)
            .createFileFromText("preview.java", language, document.text)
    }

    private class JavaI18nInlayHintsCollector(
        editor: Editor,
        private val file: PsiFile
    ) : FactoryInlayHintsCollector(editor) {

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (element !is PsiLiteralExpression) return true
            if (element.value !is String) return true

            val match = SpringMessagePatternMatcher.extractKey(element) ?: return true

            val project = element.project
            val settings = I18nSettingsState.getInstance(project)
            if (settings.state.displayMode == I18nDisplayMode.TRANSLATION_ONLY) return true

            val cacheService = I18nCacheService.getInstance(project)
            val locale = settings.getDisplayLocaleOrNull()
            val key = match.key

            val translationText = if (locale != null) {
                val translation = cacheService.getTranslationForModule(key, file.virtualFile, locale)
                if (translation != null) {
                    truncateText(translation.value, 50)
                } else {
                    val hasAnyTranslation = cacheService.getAllTranslationsForModule(key, file.virtualFile).isNotEmpty()
                    if (hasAnyTranslation) {
                        "\u26A0 Missing translation for '$locale'"
                    } else {
                        return true // Key doesn't exist
                    }
                }
            } else {
                val translation = cacheService.getTranslationForModule(key, file.virtualFile, null)
                    ?: return true
                truncateText(translation.value, 50)
            }

            val offset = element.textRange.endOffset
            val presentation = createPresentation(factory, translationText)
            sink.addInlineElement(offset, true, presentation, false)

            return true
        }

        private fun createPresentation(factory: PresentationFactory, text: String): InlayPresentation {
            val textPresentation = factory.smallText(" \u2192 $text")
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
