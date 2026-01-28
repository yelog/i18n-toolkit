package com.github.yelog.i18ntoolkit.searcheverywhere

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
import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
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
        const val SEARCH_PROVIDER_ID = "I18nToolkit.SearchEverywhere.I18n"
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

        val tokens = tokenizePattern(query)
        val compactQuery = compactString(query, preserveDot = false)
        if (tokens.isEmpty() && compactQuery.isEmpty()) return

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

            val score = calculateMatchScore(key, translations.values, tokens, compactQuery)
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
        tokens: List<String>,
        compactQuery: String
    ): Int {
        val keyLower = key.lowercase(Locale.ROOT)
        val valuesLower = entries.map { it.value.lowercase(Locale.ROOT) }
        val keyCompact = compactString(keyLower, preserveDot = false)
        val valuesCompact = entries.map { compactString(it.value, preserveDot = false) }

        val tokenMatch = tokens.isNotEmpty() && tokens.all { token ->
            keyLower.contains(token) || valuesLower.any { it.contains(token) }
        }

        val compactMatch = compactQuery.isNotEmpty() && (
            keyCompact.contains(compactQuery) ||
                isSubsequence(compactQuery, keyCompact) ||
                valuesCompact.any { it.contains(compactQuery) || isSubsequence(compactQuery, it) }
            )

        if (!tokenMatch && !compactMatch) return 0

        var score = 0

        if (tokenMatch) {
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
        }

        if (compactMatch) {
            if (compactQuery.isNotEmpty()) {
                score += when {
                    keyCompact.startsWith(compactQuery) -> 800
                    keyCompact.contains(compactQuery) -> 400
                    isSubsequence(compactQuery, keyCompact) -> 150
                    else -> 0
                }
                if (valuesCompact.any { it.contains(compactQuery) || isSubsequence(compactQuery, it) }) {
                    score += 20
                }
            }
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
        val tokens = tokenizePattern(pattern)
        val compactPattern = compactString(pattern, preserveDot = false)

        // Display key first (as main search content)
        appendWithHighlight(value.key, tokens, compactPattern, SimpleTextAttributes.REGULAR_ATTRIBUTES, selected)
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

        // Get display locale from settings
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Get the translation entry for display locale (or first available)
        val displayEntry = if (displayLocale != null) {
            value.translations[displayLocale] ?: value.translations.values.firstOrNull()
        } else {
            // Prefer zh_CN, zh, en, then first available
            value.translations["zh_CN"] ?: value.translations["zh"]
                ?: value.translations["en"] ?: value.translations.values.firstOrNull()
        }

        if (displayEntry != null) {
            // Show translation value
            val truncatedValue = truncate(displayEntry.value, 50)
            appendWithHighlight(truncatedValue, tokens, compactPattern, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)

            // Show file path and line number
            val location = buildLocationText(displayEntry)
            if (location.isNotEmpty()) {
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(location, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        toolTipText = value.key
    }

    private fun appendWithHighlight(
        text: String,
        tokens: List<String>,
        compactPattern: String,
        baseAttributes: SimpleTextAttributes,
        selected: Boolean
    ) {
        if (tokens.isEmpty() && compactPattern.isEmpty()) {
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

        if (matchRanges.isEmpty() && compactPattern.isNotEmpty()) {
            matchRanges.addAll(findCompactMatchRanges(text, compactPattern))
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

        val lineNumber = com.intellij.openapi.application.ReadAction.compute<Int, Throwable> {
            try {
                val document = FileDocumentManager.getInstance().getDocument(entry.file)
                if (document != null && entry.offset >= 0 && entry.offset <= document.textLength) {
                    document.getLineNumber(entry.offset) + 1
                } else {
                    0
                }
            } catch (e: Exception) {
                0
            }
        }
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

private val TOKEN_SPLIT_REGEX = Regex("[\\s_]+|[\\p{Punct}&&[^.]]+")
private val SEPARATOR_REGEX = Regex("[\\s_]+|\\p{Punct}+")
private val SEPARATOR_REGEX_EXCEPT_DOT = Regex("[\\s_]+|[\\p{Punct}&&[^.]]+")

private fun tokenizePattern(pattern: String): List<String> {
    return pattern.lowercase(Locale.ROOT)
        .split(TOKEN_SPLIT_REGEX)
        .filter { it.isNotEmpty() }
}

private fun compactString(value: String, preserveDot: Boolean): String {
    val regex = if (preserveDot) SEPARATOR_REGEX_EXCEPT_DOT else SEPARATOR_REGEX
    return value.lowercase(Locale.ROOT).replace(regex, "")
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    if (needle.isEmpty()) return true
    var i = 0
    for (ch in haystack) {
        if (ch == needle[i]) {
            i++
            if (i == needle.length) return true
        }
    }
    return false
}

private fun findCompactMatchRanges(text: String, compactPattern: String): List<IntRange> {
    if (compactPattern.isEmpty()) return emptyList()
    val (compactText, indexMap) = buildCompactTextAndIndexMap(text, preserveDot = false)
    if (compactText.isEmpty()) return emptyList()

    val needle = compactPattern.lowercase(Locale.ROOT)
    val haystack = compactText.lowercase(Locale.ROOT)

    val exactIndex = haystack.indexOf(needle)
    if (exactIndex >= 0) {
        return buildRangesFromCompactSpan(indexMap, exactIndex, exactIndex + needle.length - 1)
    }

    val subsequenceIndices = findSubsequenceMatchIndices(needle, haystack, indexMap)
    if (subsequenceIndices.isEmpty()) return emptyList()
    return buildRangesFromOriginalIndices(subsequenceIndices)
}

private fun buildCompactTextAndIndexMap(text: String, preserveDot: Boolean): Pair<String, List<Int>> {
    val compact = StringBuilder(text.length)
    val indexMap = ArrayList<Int>(text.length)
    text.forEachIndexed { index, ch ->
        if (!isSeparatorChar(ch, preserveDot)) {
            compact.append(ch.lowercaseChar())
            indexMap.add(index)
        }
    }
    return compact.toString() to indexMap
}

private fun isSeparatorChar(ch: Char, preserveDot: Boolean): Boolean {
    if (ch == '_') return true
    if (ch.isWhitespace()) return true
    if (preserveDot && ch == '.') return false
    return ch.toString().matches(Regex("\\p{Punct}"))
}

private fun buildRangesFromCompactSpan(indexMap: List<Int>, start: Int, end: Int): List<IntRange> {
    if (start < 0 || end >= indexMap.size || start > end) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var rangeStart = indexMap[start]
    var prev = rangeStart
    for (i in (start + 1)..end) {
        val originalIndex = indexMap[i]
        if (originalIndex == prev + 1) {
            prev = originalIndex
        } else {
            ranges.add(rangeStart..prev)
            rangeStart = originalIndex
            prev = originalIndex
        }
    }
    ranges.add(rangeStart..prev)
    return ranges
}

private fun findSubsequenceMatchIndices(
    needle: String,
    haystack: String,
    indexMap: List<Int>
): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val indices = mutableListOf<Int>()
    var needleIndex = 0
    for (i in haystack.indices) {
        if (haystack[i] == needle[needleIndex]) {
            indices.add(indexMap[i])
            needleIndex++
            if (needleIndex == needle.length) return indices
        }
    }
    return emptyList()
}

private fun buildRangesFromOriginalIndices(indices: List<Int>): List<IntRange> {
    if (indices.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var rangeStart = indices.first()
    var prev = rangeStart
    for (i in 1 until indices.size) {
        val index = indices[i]
        if (index == prev + 1) {
            prev = index
        } else {
            ranges.add(rangeStart..prev)
            rangeStart = index
            prev = index
        }
    }
    ranges.add(rangeStart..prev)
    return ranges
}
