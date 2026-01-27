package com.github.yelog.i18nhelper.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.impl.LookupCellRenderer
import com.intellij.codeInsight.lookup.impl.LookupCustomizer
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.openapi.util.TextRange
import javax.swing.JList
import javax.swing.ListCellRenderer

class I18nLookupCustomizer : LookupCustomizer {

    override fun customizeLookup(lookup: LookupImpl) {
        val list = lookup.getList()
        val currentRenderer = list.cellRenderer as? ListCellRenderer<LookupElement> ?: return
        if (currentRenderer is I18nLookupRendererWrapper) return

        val baseRenderer = lookup.cellRenderer
        list.cellRenderer = I18nLookupRendererWrapper(currentRenderer, baseRenderer)
    }
}

private class I18nLookupRendererWrapper(
    private val delegate: ListCellRenderer<LookupElement>,
    private val lookupRenderer: LookupCellRenderer
) : ListCellRenderer<LookupElement> {

    override fun getListCellRendererComponent(
        list: JList<out LookupElement>,
        value: LookupElement,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        val component = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val info = value.getUserData(TRANSLATION_HIGHLIGHT_KEY) ?: return component

        val typeLabel = typeLabelField?.get(lookupRenderer) as? SimpleColoredComponent ?: return component
        applyHighlight(typeLabel, info)
        return component
    }
}

private val typeLabelField = runCatching {
    LookupCellRenderer::class.java.getDeclaredField("typeLabel").apply { isAccessible = true }
}.getOrNull()

private fun applyHighlight(label: SimpleColoredComponent, info: TranslationHighlightInfo) {
    val text = label.getCharSequence(false).toString()
    val input = info.input
    if (text.isBlank()) return
    if (input.isBlank()) return

    val ranges = computeHighlightRanges(text, input)
    if (ranges.isEmpty()) return

    label.clear()
    val normalAttrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, label.foreground)
    var last = 0
    for (range in ranges) {
        if (range.startOffset > last) {
            label.append(text.substring(last, range.startOffset), normalAttrs)
        }
        val end = range.endOffset.coerceAtMost(text.length)
        if (end > range.startOffset) {
            label.append(text.substring(range.startOffset, end), LookupCellRenderer.REGULAR_MATCHED_ATTRIBUTES)
        }
        last = end
    }
    if (last < text.length) {
        label.append(text.substring(last), normalAttrs)
    }
}

private fun computeHighlightRanges(text: String, input: String): List<TextRange> {
    val textLower = text.lowercase()
    val inputLower = input.lowercase()
    val ranges = mutableListOf<TextRange>()

    var index = textLower.indexOf(inputLower)
    while (index >= 0) {
        ranges.add(TextRange(index, index + inputLower.length))
        index = textLower.indexOf(inputLower, index + inputLower.length)
    }

    if (ranges.isNotEmpty()) {
        return mergeRanges(ranges)
    }

    val words = inputLower.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
    for (word in words) {
        var wordIndex = textLower.indexOf(word)
        while (wordIndex >= 0) {
            ranges.add(TextRange(wordIndex, wordIndex + word.length))
            wordIndex = textLower.indexOf(word, wordIndex + word.length)
        }
    }

    return mergeRanges(ranges)
}

private fun mergeRanges(ranges: List<TextRange>): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedWith(compareBy({ it.startOffset }, { it.endOffset }))
    val merged = mutableListOf<TextRange>()
    var current = sorted.first()
    for (range in sorted.drop(1)) {
        current = if (range.startOffset <= current.endOffset) {
            TextRange(current.startOffset, maxOf(current.endOffset, range.endOffset))
        } else {
            merged.add(current)
            range
        }
    }
    merged.add(current)
    return merged
}
