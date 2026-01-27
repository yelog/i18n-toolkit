package com.github.yelog.i18nhelper.completion

import com.intellij.openapi.util.Key
data class TranslationHighlightInfo(
    val input: String
)

val TRANSLATION_HIGHLIGHT_KEY: Key<TranslationHighlightInfo> =
    Key.create("i18n.translation.highlight")
