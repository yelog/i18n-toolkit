package com.github.yelog.i18nhelper.searcheverywhere

import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.util.Locale
import javax.swing.JList
import javax.swing.ListCellRenderer
import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.actions.searcheverywhere.ContributorSearchResult
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereUI
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import com.github.yelog.i18nhelper.model.TranslationEntry
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import java.awt.Color

class I18nSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<I18nSearchItem> {

    override fun createContributor(event: AnActionEvent): SearchEverywhereContributor<I18nSearchItem> {
        val project = event.getRequiredData(CommonDataKeys.PROJECT)
        return I18nSearchEverywhereContributor(project)
    }

    override fun isAvailable(project: Project?): Boolean = project != null
}

/**
 * Represents a merged search item with a key and all its translations across locales.
 */
data class I18nSearchItem(
    val key: String,
    val translations: Map<String, TranslationEntry>
)

class I18nSearchEverywhereContributor(
    private val project: Project
) : SearchEverywhereContributor<I18nSearchItem> {

    companion object {
        const val SEARCH_PROVIDER_ID = "I18nHelper.SearchEverywhere.I18n"
        private const val GROUP_NAME = "I18n"
        private const val PAGE_SIZE = 15
    }

    private var currentPattern: String = ""

    override fun getSearchProviderId(): String = SEARCH_PROVIDER_ID

    override fun getGroupName(): String = GROUP_NAME

    override fun getSortWeight(): Int = 450

    override fun showInFindResults(): Boolean = true

    override fun isShownInSeparateTab(): Boolean = true

    override fun getAdvertisement(): String? {
        return "Press Enter to copy key, Ctrl+Enter to navigate to file"
    }

    override fun fetchElements(pattern: String, indicator: ProgressIndicator, processor: Processor<in I18nSearchItem>) {
        val query = pattern.trim()
        currentPattern = query
        if (query.isEmpty()) return

        val tokens = query.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        // Collect all matching items with their match scores
        val matchedItems = mutableListOf<Pair<I18nSearchItem, Int>>()
        val allKeys = cacheService.getAllKeys()
        var checked = 0

        for (key in allKeys) {
            indicator.checkCanceled()

            val translations = cacheService.getAllTranslations(key)
            if (translations.isEmpty()) continue

            val score = calculateMatchScore(key, translations.values, tokens)
            if (score > 0) {
                matchedItems.add(I18nSearchItem(key, translations) to score)
            }

            checked++
            if (checked % 200 == 0) {
                indicator.checkCanceled()
            }
        }

        // Sort by score (higher is better), then by key length (shorter is better), then alphabetically
        matchedItems.sortWith(compareBy(
            { -it.second },           // Higher score first
            { it.first.key.length },  // Shorter key first
            { it.first.key }          // Alphabetically
        ))

        // Process sorted items
        for ((item, _) in matchedItems) {
            indicator.checkCanceled()
            if (!processor.process(item)) {
                return
            }
        }
    }

    /**
     * Calculate match score for sorting results.
     * Higher score = better match = should appear first.
     *
     * Scoring:
     * - Key starts with first token: +1000
     * - Key starts with any token: +500
     * - Key contains all tokens: +100
     * - Earlier position of first token in key: +50 (based on position)
     * - Translation values match: +10
     */
    private fun calculateMatchScore(
        key: String,
        entries: Collection<TranslationEntry>,
        tokens: List<String>
    ): Int {
        val keyLower = key.lowercase(Locale.ROOT)
        val valuesLower = entries.map { it.value.lowercase(Locale.ROOT) }

        // Check if all tokens match somewhere (key or values)
        val allTokensMatch = tokens.all { token ->
            keyLower.contains(token) || valuesLower.any { it.contains(token) }
        }
        if (!allTokensMatch) return 0

        var score = 0

        // Check key matches
        val keyMatchesAllTokens = tokens.all { keyLower.contains(it) }
        if (keyMatchesAllTokens) {
            score += 100
        }

        // Check if key starts with first token (highest priority)
        val firstToken = tokens.first()
        if (keyLower.startsWith(firstToken)) {
            score += 1000
        } else if (tokens.any { keyLower.startsWith(it) }) {
            // Key starts with any token
            score += 500
        }

        // Bonus for earlier position of first token match in key
        val firstTokenIndex = keyLower.indexOf(firstToken)
        if (firstTokenIndex >= 0) {
            // Earlier position = higher score (max 50 points)
            score += maxOf(0, 50 - firstTokenIndex)
        }

        // Small bonus if values also match
        val valuesMatchAllTokens = tokens.all { token ->
            valuesLower.any { it.contains(token) }
        }
        if (valuesMatchAllTokens) {
            score += 10
        }

        return score
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
        // Check if Ctrl is pressed - navigate to file instead of copying
        val isCtrlPressed = (modifiers and InputEvent.CTRL_DOWN_MASK) != 0

        if (isCtrlPressed) {
            // Navigate to the translation file
            return navigateToFile(selected)
        }

        // Default: Copy the key to clipboard
        CopyPasteManager.getInstance().setContents(StringSelection(selected.key))

        // Show hint in the current editor if available
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null) {
            HintManager.getInstance().showInformationHint(
                editor,
                "Copied: ${selected.key} (Ctrl+Enter to navigate to file)"
            )
        }

        return true
    }

    private fun navigateToFile(selected: I18nSearchItem): Boolean {
        // Get display locale from settings
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Find the entry for display locale, or fall back to first available
        val entry = if (displayLocale != null) {
            selected.translations[displayLocale] ?: selected.translations.values.firstOrNull()
        } else {
            // Prefer zh_CN, zh, en, then first available
            selected.translations["zh_CN"] ?: selected.translations["zh"]
                ?: selected.translations["en"] ?: selected.translations.values.firstOrNull()
        }

        if (entry == null) return false

        val descriptor = OpenFileDescriptor(project, entry.file, entry.offset)
        if (descriptor.canNavigate()) {
            descriptor.navigate(true)
            return true
        }
        return false
    }

    override fun getElementsRenderer(): ListCellRenderer<in I18nSearchItem> {
        return I18nSearchEverywhereRenderer(project, { currentPattern })
    }

    override fun getDataForItem(element: I18nSearchItem, dataId: String): Any? {
        if (CommonDataKeys.VIRTUAL_FILE.`is`(dataId)) {
            // Return the file for the display locale or first available
            val settings = I18nSettingsState.getInstance(project)
            val displayLocale = settings.getDisplayLocaleOrNull()
            val entry = if (displayLocale != null) {
                element.translations[displayLocale] ?: element.translations.values.firstOrNull()
            } else {
                element.translations.values.firstOrNull()
            }
            return entry?.file
        }
        return null
    }

    private fun adjustLimit(limit: Int): Int {
        if (limit <= 0) return PAGE_SIZE
        if (limit <= SearchEverywhereUI.MULTIPLE_CONTRIBUTORS_ELEMENTS_LIMIT) return PAGE_SIZE
        val singleLimit = SearchEverywhereUI.SINGLE_CONTRIBUTOR_ELEMENTS_LIMIT
        val pages = (limit + singleLimit - 1) / singleLimit
        return (pages * PAGE_SIZE).coerceAtLeast(PAGE_SIZE)
    }
}

private class I18nSearchEverywhereRenderer(
    private val project: Project,
    private val patternProvider: () -> String
) : ColoredListCellRenderer<I18nSearchItem>() {

    private val highlightColor = JBColor(Color(255, 200, 0), Color(120, 100, 0))
    private val highlightAttributes = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_SEARCH_MATCH,
        null
    )

    override fun customizeCellRenderer(
        list: JList<out I18nSearchItem>,
        value: I18nSearchItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (value == null) return

        val pattern = patternProvider()
        val tokens = pattern.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        // Display key first (as main search content)
        appendWithHighlight(value.key, tokens, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, selected)
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

        // Display translations for each locale
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Sort locales: display locale first, then alphabetically
        val sortedLocales = value.translations.keys.sortedWith(compareBy(
            { if (displayLocale != null && it == displayLocale) 0 else 1 },
            { it }
        ))

        var first = true
        for (locale in sortedLocales) {
            val entry = value.translations[locale] ?: continue

            if (!first) {
                append(" | ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            first = false

            // Locale tag
            append("[", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(locale, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append("]", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            // Translation value with highlight
            val truncatedValue = truncate(entry.value, 40)
            appendWithHighlight(truncatedValue, tokens, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)
        }

        // Location info for display locale
        val displayEntry = if (displayLocale != null) {
            value.translations[displayLocale] ?: value.translations.values.firstOrNull()
        } else {
            value.translations.values.firstOrNull()
        }

        if (displayEntry != null) {
            val location = buildLocationText(displayEntry)
            if (location.isNotEmpty()) {
                append("  ")
                append(location, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        toolTipText = value.key
    }

    private fun appendWithHighlight(
        text: String,
        tokens: List<String>,
        baseAttributes: SimpleTextAttributes,
        selected: Boolean
    ) {
        if (tokens.isEmpty()) {
            append(text, baseAttributes)
            return
        }

        val textLower = text.lowercase(Locale.ROOT)
        val matchRanges = mutableListOf<IntRange>()

        // Find all matching ranges
        for (token in tokens) {
            var startIndex = 0
            while (true) {
                val index = textLower.indexOf(token, startIndex)
                if (index < 0) break
                matchRanges.add(index until index + token.length)
                startIndex = index + 1
            }
        }

        if (matchRanges.isEmpty()) {
            append(text, baseAttributes)
            return
        }

        // Merge overlapping ranges
        val mergedRanges = mergeRanges(matchRanges.sortedBy { it.first })

        // Append text with highlights
        var currentPos = 0
        for (range in mergedRanges) {
            if (currentPos < range.first) {
                append(text.substring(currentPos, range.first), baseAttributes)
            }
            append(text.substring(range.first, range.last + 1), highlightAttributes)
            currentPos = range.last + 1
        }
        if (currentPos < text.length) {
            append(text.substring(currentPos), baseAttributes)
        }
    }

    private fun mergeRanges(sortedRanges: List<IntRange>): List<IntRange> {
        if (sortedRanges.isEmpty()) return emptyList()

        val result = mutableListOf<IntRange>()
        var current = sortedRanges.first()

        for (i in 1 until sortedRanges.size) {
            val next = sortedRanges[i]
            if (next.first <= current.last + 1) {
                // Overlapping or adjacent, merge
                current = current.first..maxOf(current.last, next.last)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)

        return result
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
