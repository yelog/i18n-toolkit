package com.github.yelog.i18nhelper.searcheverywhere

import java.util.Locale
import javax.swing.JList
import javax.swing.ListCellRenderer
import com.intellij.ide.actions.searcheverywhere.ContributorSearchResult
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereUI
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import com.github.yelog.i18nhelper.model.TranslationEntry
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nUiRefresher

class I18nSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<I18nSearchItem> {

    override fun createContributor(event: AnActionEvent): SearchEverywhereContributor<I18nSearchItem> {
        val project = event.getRequiredData(CommonDataKeys.PROJECT)
        return I18nSearchEverywhereContributor(project)
    }

    override fun isAvailable(project: Project?): Boolean = project != null
}

data class I18nSearchItem(
    val entry: TranslationEntry
)

class I18nSearchEverywhereContributor(
    private val project: Project
) : SearchEverywhereContributor<I18nSearchItem> {

    companion object {
        const val SEARCH_PROVIDER_ID = "I18nHelper.SearchEverywhere.I18n"
        private const val GROUP_NAME = "I18n"
        private const val PAGE_SIZE = 15
    }

    override fun getSearchProviderId(): String = SEARCH_PROVIDER_ID

    override fun getGroupName(): String = GROUP_NAME

    override fun getSortWeight(): Int = 450

    override fun showInFindResults(): Boolean = true

    override fun isShownInSeparateTab(): Boolean = true

    override fun getAdvertisement(): String? {
        val shortcutText = getShortcutText(I18nUiRefresher.COPY_KEY_ACTION_ID)
        return "Copy key shortcut: $shortcutText"
    }

    override fun fetchElements(pattern: String, indicator: ProgressIndicator, processor: Processor<in I18nSearchItem>) {
        val query = pattern.trim()
        if (query.isEmpty()) return

        val tokens = query.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        var checked = 0
        val files = cacheService.getTranslationFiles()
        for (file in files) {
            for (entry in file.entries.values) {
                indicator.checkCanceled()
                if (matches(entry, tokens)) {
                    if (!processor.process(I18nSearchItem(entry))) {
                        return
                    }
                }
                checked++
                if (checked % 200 == 0) {
                    indicator.checkCanceled()
                }
            }
        }
    }

    override fun search(
        pattern: String,
        indicator: ProgressIndicator,
        limit: Int
    ): ContributorSearchResult<I18nSearchItem> {
        val effectiveLimit = adjustLimit(limit)
        return super<SearchEverywhereContributor>.search(pattern, indicator, effectiveLimit)
    }

    override fun processSelectedItem(selected: I18nSearchItem, modifiers: Int, searchText: String): Boolean {
        val descriptor = OpenFileDescriptor(project, selected.entry.file, selected.entry.offset)
        if (descriptor.canNavigate()) {
            descriptor.navigate(true)
            return true
        }
        return false
    }

    override fun getElementsRenderer(): ListCellRenderer<in I18nSearchItem> {
        return I18nSearchEverywhereRenderer(project)
    }

    override fun getDataForItem(element: I18nSearchItem, dataId: String): Any? {
        return if (CommonDataKeys.VIRTUAL_FILE.`is`(dataId)) {
            element.entry.file
        } else {
            null
        }
    }

    private fun adjustLimit(limit: Int): Int {
        if (limit <= 0) return PAGE_SIZE
        if (limit <= SearchEverywhereUI.MULTIPLE_CONTRIBUTORS_ELEMENTS_LIMIT) return PAGE_SIZE
        val singleLimit = SearchEverywhereUI.SINGLE_CONTRIBUTOR_ELEMENTS_LIMIT
        val pages = (limit + singleLimit - 1) / singleLimit
        return (pages * PAGE_SIZE).coerceAtLeast(PAGE_SIZE)
    }

    private fun matches(entry: TranslationEntry, tokens: List<String>): Boolean {
        val key = entry.key.lowercase(Locale.ROOT)
        val value = entry.value.lowercase(Locale.ROOT)
        val locale = entry.locale.lowercase(Locale.ROOT)
        return tokens.all { token ->
            key.contains(token) || value.contains(token) || locale.contains(token)
        }
    }

    private fun getShortcutText(actionId: String): String {
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcut = keymap.getShortcuts(actionId)
            .filterIsInstance<KeyboardShortcut>()
            .firstOrNull()
        return shortcut?.let { KeymapUtil.getShortcutText(it) } ?: "Not set"
    }
}

private class I18nSearchEverywhereRenderer(
    private val project: Project
) : ColoredListCellRenderer<I18nSearchItem>() {

    override fun customizeCellRenderer(
        list: JList<out I18nSearchItem>,
        value: I18nSearchItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (value == null) return

        val entry = value.entry
        append("[")
        append(entry.locale, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("] ")
        append(truncate(entry.value, 80), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("  ")
        append(entry.key, SimpleTextAttributes.GRAYED_ATTRIBUTES)

        val location = buildLocationText(entry)
        if (location.isNotEmpty()) {
            append("  ")
            append(location, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        toolTipText = "${entry.key} (${entry.locale})"
    }

    private fun buildLocationText(entry: TranslationEntry): String {
        val basePath = project.basePath ?: return ""
        val filePath = entry.file.path
        val relativePath = if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length).trimStart('/')
        } else {
            entry.file.name
        }

        val document = FileDocumentManager.getInstance().getDocument(entry.file)
        val lineNumber = document?.getLineNumber(entry.offset)?.plus(1) ?: 0
        return if (lineNumber > 0) "$relativePath:$lineNumber" else relativePath
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 3) + "..."
        } else {
            text
        }
    }
}
