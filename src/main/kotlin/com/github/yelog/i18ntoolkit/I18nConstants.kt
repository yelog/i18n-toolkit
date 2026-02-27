package com.github.yelog.i18ntoolkit

/**
 * Constants used throughout the i18n toolkit plugin.
 * Centralizing these values makes the code more maintainable and self-documenting.
 */
object I18nConstants {

    /**
     * UI and display constants
     */
    object Display {
        /** Maximum length for translation preview text in completion and hints */
        const val TRANSLATION_PREVIEW_MAX_LENGTH = 50

        /** Maximum length for a single line display in navigation popup */
        const val LINE_CONTENT_MAX_LENGTH = 60

        /** Truncation suffix used when text exceeds max length */
        const val TRUNCATION_SUFFIX = "..."
    }

    /**
     * Caching and performance constants
     */
    object Cache {
        /** Maximum size for global processed hints cache to prevent memory leaks */
        const val MAX_HINTS_CACHE_SIZE = 10000
    }

    /**
     * Completion ranking score weights.
     * Higher scores indicate better matches and appear first in completion results.
     */
    object CompletionWeights {
        /** Score for exact key match - highest priority */
        const val EXACT_MATCH = 10000

        /** Score bonus when key starts with the input text */
        const val PREFIX_MATCH = 5000

        /** Score bonus when the first segment of key matches input */
        const val FIRST_SEGMENT_MATCH = 3000

        /** Score bonus when first segment contains the input */
        const val FIRST_SEGMENT_CONTAINS = 1000

        /** Score bonus when the key belongs to current namespace */
        const val NAMESPACE_MATCH = 1500

        /** Score bonus when input appears as substring in key */
        const val SUBSTRING_MATCH = 2000

        /** Score bonus when all input words appear in key (fuzzy match) */
        const val ALL_WORDS_MATCH = 1000

        /** Score bonus when input words appear in the same order in key */
        const val WORDS_IN_ORDER = 500

        /** Score bonus for acronym match (e.g., "un" matches "user.name") */
        const val ACRONYM_MATCH = 300

        /** Base score ensuring all keys are included in results */
        const val BASE_SCORE = 1

        /** Maximum length bonus for shorter keys (prefer specific over general) */
        const val MAX_LENGTH_BONUS = 200

        /** Position bonus multiplier for substring position (earlier is better) */
        const val POSITION_BONUS_MAX = 100

        /** Bonus for exact translation match */
        const val TRANSLATION_EXACT_MATCH = 6000

        /** Bonus when translation starts with input */
        const val TRANSLATION_PREFIX_MATCH = 3000

        /** Bonus when translation contains input */
        const val TRANSLATION_SUBSTRING_MATCH = 1500

        /** Position bonus multiplier for translation position */
        const val TRANSLATION_POSITION_BONUS_MAX = 80

        /** Bonus when all input words appear in translation */
        const val TRANSLATION_WORDS_MATCH = 800
    }

    /**
     * PSI tree traversal constants
     */
    object PsiTraversal {
        /** Maximum depth for traversing up the PSI tree to find literal expressions */
        const val MAX_LITERAL_SEARCH_DEPTH = 5
    }
}
