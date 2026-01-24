package com.github.yelog.i18nhelper.marker

import com.github.yelog.i18nhelper.model.TranslationEntry
import com.github.yelog.i18nhelper.scanner.I18nDirectoryScanner
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.awt.event.MouseEvent

class I18nLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        elements.forEach { element ->
            when (element) {
                is JsonProperty -> processJsonProperty(element, result)
                is JSProperty -> processJsProperty(element, result)
            }
        }
    }

    private fun processJsonProperty(property: JsonProperty, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = property.containingFile?.virtualFile ?: return
        if (!I18nDirectoryScanner.isTranslationFile(file)) return

        val project = property.project
        val cacheService = I18nCacheService.getInstance(project)
        val translationFile = cacheService.getTranslationFile(file) ?: return

        val fullKey = buildFullKey(property, translationFile.keyPrefix)
        val otherLocales = cacheService.getOtherLocaleFiles(file, fullKey)

        if (otherLocales.isNotEmpty()) {
            val nameElement = property.nameElement ?: return
            createLineMarker(nameElement, fullKey, otherLocales, project, result)
        }
    }

    private fun processJsProperty(property: JSProperty, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = property.containingFile?.virtualFile ?: return
        if (!I18nDirectoryScanner.isTranslationFile(file)) return

        val project = property.project
        val cacheService = I18nCacheService.getInstance(project)
        val translationFile = cacheService.getTranslationFile(file) ?: return

        val fullKey = buildFullKey(property, translationFile.keyPrefix)
        val otherLocales = cacheService.getOtherLocaleFiles(file, fullKey)

        if (otherLocales.isNotEmpty()) {
            val nameIdentifier = property.nameIdentifier ?: return
            createLineMarker(nameIdentifier, fullKey, otherLocales, project, result)
        }
    }

    private fun buildFullKey(property: PsiElement, prefix: String): String {
        val keyParts = mutableListOf<String>()
        var current: PsiElement? = property

        while (current != null) {
            when (current) {
                is JsonProperty -> {
                    keyParts.add(0, current.name)
                    current = current.parent?.parent
                }
                is JSProperty -> {
                    current.name?.let { keyParts.add(0, it) }
                    current = current.parent?.parent
                }
                else -> current = current.parent
            }
        }

        val key = keyParts.joinToString(".")
        return if (prefix.isEmpty()) key else "$prefix$key"
    }

    private fun createLineMarker(
        element: PsiElement,
        key: String,
        entries: List<TranslationEntry>,
        project: com.intellij.openapi.project.Project,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        val psiManager = PsiManager.getInstance(project)
        val targets = entries.mapNotNull { entry ->
            val psiFile = psiManager.findFile(entry.file) ?: return@mapNotNull null
            psiFile.findElementAt(entry.offset) as? NavigatablePsiElement
        }

        if (targets.isEmpty()) return

        val marker = LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Forward,
            { "Navigate to other locales for '$key'" },
            { e, elt ->
                if (targets.size == 1) {
                    targets[0].navigate(true)
                } else {
                    PsiElementListNavigator.openTargets(
                        e,
                        targets.toTypedArray(),
                        "Choose Locale",
                        "Other locale translations for '$key'",
                        DefaultPsiElementCellRenderer()
                    )
                }
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "I18n: Other locales" }
        )

        result.add(marker)
    }
}
