package com.github.yelog.i18ntoolkit.completion

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nFunctionResolver
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Provides auto-completion for i18n keys inside t(), $t(), and other i18n function calls.
 * Shows fuzzy-matched keys with the best matches at the top.
 */
class I18nKeyCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val logger = thisLogger()
        logger.info("=== I18n fillCompletionVariants called ===")
        logger.info("Completion type: ${parameters.completionType}")
        logger.info("Position: ${parameters.position.javaClass.simpleName}, text: '${parameters.position.text}'")

        // Process i18n completion
        processI18nCompletion(parameters, result)

        // Continue with other contributors
        super.fillCompletionVariants(parameters, result)
    }

    private fun processI18nCompletion(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val project = position.project

        val logger = thisLogger()
        logger.info("I18n completion triggered")
        logger.info("Position element: ${position.javaClass.simpleName}, text: '${position.text}'")
        logger.info("Position parent: ${position.parent?.javaClass?.simpleName}")
        logger.info("Position parent.parent: ${position.parent?.parent?.javaClass?.simpleName}")
        logger.info("Position parent.parent.parent: ${position.parent?.parent?.parent?.javaClass?.simpleName}")

        // Find the JSLiteralExpression by traversing up the tree
        var current = position
        var literalExpression: JSLiteralExpression? = null
        var depth = 0
        while (current.parent != null && depth < 5) {
            logger.info("Checking depth $depth: ${current.javaClass.simpleName}")
            if (current is JSLiteralExpression) {
                literalExpression = current
                logger.info("Found JSLiteralExpression at depth $depth")
                break
            }
            current = current.parent
            depth++
        }

        if (literalExpression == null) {
            // Try position.parent directly
            literalExpression = position.parent as? JSLiteralExpression
            if (literalExpression == null) {
                logger.info("Not inside JSLiteralExpression after traversing up to depth $depth, returning")
                return
            }
        }

        // Check if we're inside an i18n function call
        // PSI structure: JSCallExpression -> JSArgumentList -> JSLiteralExpression
        var callExpression: JSCallExpression? = null

        // Try literalExpression.parent first (might be JSArgumentList)
        val parent = literalExpression.parent
        logger.info("Literal parent: ${parent?.javaClass?.simpleName}")

        if (parent is JSCallExpression) {
            callExpression = parent
        } else if (parent != null) {
            // Try parent.parent (for JSArgumentList case)
            logger.info("Literal parent.parent: ${parent.parent?.javaClass?.simpleName}")
            callExpression = parent.parent as? JSCallExpression
        }

        if (callExpression == null) {
            logger.info("Could not find JSCallExpression, returning")
            return
        }

        logger.info("Found JSCallExpression")

        val methodExpr = callExpression.methodExpression as? JSReferenceExpression
        if (methodExpr == null) {
            logger.info("Method expression is not JSReferenceExpression, returning")
            return
        }

        val methodName = methodExpr.referenceName
        if (methodName == null) {
            logger.info("Method name is null, returning")
            return
        }

        logger.info("Method name: $methodName")

        // Check if this is an i18n function
        val i18nFunctions = I18nFunctionResolver.getFunctions(project)
        logger.info("Configured i18n functions: $i18nFunctions")
        if (!i18nFunctions.contains(methodName)) {
            logger.info("Method '$methodName' is not an i18n function, returning")
            return
        }

        // Calculate the correct prefix from the string literal content
        // This is more reliable than result.prefixMatcher.prefix which may include text outside the string
        val stringContent = literalExpression.stringValue ?: ""
        val cursorOffsetInFile = parameters.offset
        val literalStartInFile = literalExpression.textRange.startOffset
        val contentStartInFile = literalStartInFile + 1  // +1 to skip opening quote

        // Calculate how much of the string content is before the cursor
        val cursorOffsetInContent = (cursorOffsetInFile - contentStartInFile).coerceIn(0, stringContent.length)
        val currentText = stringContent.substring(0, cursorOffsetInContent)
        val currentTextLower = currentText.lowercase()

        logger.info("Current input text: '$currentText', full string: '$stringContent'")

        // Get all available keys
        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()
        val allKeys = cacheService.getAllKeys()
        logger.info("Total available keys: ${allKeys.size}")

        if (allKeys.isEmpty()) {
            logger.info("No keys available in cache, returning")
            return
        }

        // Resolve namespace context (remove trailing dot)
        val namespaceWithDot = I18nNamespaceResolver.resolveNamespace(callExpression)
        val namespace = if (namespaceWithDot.isNotEmpty()) {
            namespaceWithDot.removeSuffix(".")
        } else {
            null
        }

        // Get display locale for showing translations and matching
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Precompute display translations for fuzzy matching (display locale only)
        val displayTranslations = mutableMapOf<String, String?>()
        val typeTexts = mutableMapOf<String, String?>()
        for (key in allKeys) {
            val displayTranslation = if (displayLocale != null) {
                cacheService.getTranslationStrict(key, displayLocale)?.value
            } else {
                null
            }
            displayTranslations[key] = displayTranslation
            typeTexts[key] = displayTranslation ?: cacheService.getTranslation(key)?.value
        }

        // Filter and rank keys based on fuzzy matching (key + display translation)
        val rankedKeys = rankKeysByMatch(
            currentText,
            allKeys.toList(),
            namespace
        ) { key -> displayTranslations[key] }
        logger.info("Ranked keys count: ${rankedKeys.size}")

        // Use a custom prefix matcher that accepts all keys (we do our own fuzzy filtering)
        // IMPORTANT: Use the original prefix from result to maintain correct popup position
        val fuzzyResult = result.withPrefixMatcher(I18nFuzzyPrefixMatcher(currentText))

        // Create a map to store scores for each key (used by custom sorter)
        val keyScores = mutableMapOf<String, Int>()
        for ((key, score) in rankedKeys) {
            keyScores[key] = score
        }

        // Use a custom sorter that respects our score-based ordering
        val sorter = CompletionSorter.emptySorter()
            .weigh(I18nKeyWeigher(keyScores))

        val sortedResult = fuzzyResult.withRelevanceSorter(sorter)

        // Create completion items - no artificial limit, let IntelliJ handle display
        var addedCount = 0
        for ((key, _) in rankedKeys) {
            val translationValue = typeTexts[key]
            val translationDisplay = translationValue?.take(50) ?: ""

            // Determine the key to show (remove namespace prefix if applicable)
            val displayKey = if (namespace != null && key.startsWith("$namespace.")) {
                key.removePrefix("$namespace.")
            } else {
                key
            }
            val displayKeyLower = displayKey.lowercase()

            val lookupElement = LookupElementBuilder.create(key, displayKey)  // Use key as lookupObject for weigher
                .withPresentableText(displayKey)
                .withTypeText(translationDisplay, true)
                .withIcon(com.intellij.icons.AllIcons.Nodes.Property)
                .withInsertHandler { insertContext, _ ->
                    // IntelliJ may have already done some insertion/replacement with incorrect offsets.
                    // We need to find the actual string boundaries and replace the content correctly.
                    val editor = insertContext.editor
                    val document = editor.document
                    val text = document.charsSequence
                    val caretOffset = editor.caretModel.offset

                    // Find the opening quote by searching backwards from caret
                    var quoteStart = caretOffset - 1
                    while (quoteStart >= 0) {
                        val c = text[quoteStart]
                        if (c == '\'' || c == '"') break
                        // Stop if we hit something that shouldn't be in a string key
                        if (c == '\n' || c == '\r' || c == '(' || c == ')' || c == '{' || c == '}') {
                            quoteStart = -1
                            break
                        }
                        quoteStart--
                    }

                    if (quoteStart < 0) return@withInsertHandler

                    val quoteChar = text[quoteStart]

                    // Find the closing quote by searching forward from caret
                    var quoteEnd = caretOffset
                    while (quoteEnd < text.length) {
                        val c = text[quoteEnd]
                        if (c == quoteChar) break
                        // Stop if we hit something that shouldn't be in a string
                        if (c == '\n' || c == '\r') {
                            quoteEnd = text.length
                            break
                        }
                        quoteEnd++
                    }

                    if (quoteEnd >= text.length) return@withInsertHandler

                    // Replace the content between quotes (exclusive of quotes themselves)
                    val contentStart = quoteStart + 1
                    val currentContent = text.substring(contentStart, quoteEnd)

                    // Only replace if content is different
                    if (currentContent != displayKey) {
                        document.replaceString(contentStart, quoteEnd, displayKey)
                    }
                    editor.caretModel.moveToOffset(contentStart + displayKey.length)
                }

            val shouldHighlightTranslation = translationDisplay.isNotBlank() &&
                currentTextLower.isNotBlank() &&
                !displayKeyLower.contains(currentTextLower) &&
                hasTranslationMatch(translationDisplay, currentText)

            if (shouldHighlightTranslation) {
                lookupElement.putUserData(
                    TRANSLATION_HIGHLIGHT_KEY,
                    TranslationHighlightInfo(currentText)
                )
            }

            // Add element to the sorted result
            sortedResult.addElement(lookupElement)
            addedCount++
        }
        logger.info("Successfully added $addedCount completion items")
    }

    /**
     * Rank keys by how well they match the input text.
     * Returns a list of (key, score) pairs sorted by score (higher is better).
     * All keys are included - matching keys have higher scores.
     */
    private fun rankKeysByMatch(
        input: String,
        keys: List<String>,
        namespace: String?,
        translationProvider: (String) -> String?
    ): List<Pair<String, Int>> {
        if (input.isBlank()) {
            // If no input, show all keys sorted alphabetically with base score
            return keys.map { it to 1 }.sortedBy { it.first }
        }

        val inputLower = input.lowercase()
        val inputKeyWords = inputLower.split(Regex("[.\\-_\\s]+")).filter { it.isNotEmpty() }
        val inputTranslationWords = inputLower.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }

        // Calculate scores for all keys and sort by score (higher first), then alphabetically
        return keys.map { key ->
            val translation = translationProvider(key)
            val score = calculateMatchScore(
                key,
                inputLower,
                inputKeyWords,
                inputTranslationWords,
                namespace,
                translation
            )
            key to score
        }.sortedWith(compareBy({ -it.second }, { it.first }))
    }

    /**
     * Calculate match score for a key.
     * Higher score = better match.
     * All keys get a base score of 1, matching keys get higher scores.
     */
    private fun calculateMatchScore(
        key: String,
        inputLower: String,
        inputKeyWords: List<String>,
        inputTranslationWords: List<String>,
        namespace: String?,
        translation: String?
    ): Int {
        val keyLower = key.lowercase()
        // Base score ensures all keys are included
        var score = 1

        // Remove namespace prefix for matching
        val keyForMatching = if (namespace != null && keyLower.startsWith("$namespace.")) {
            keyLower.removePrefix("$namespace.")
        } else {
            keyLower
        }

        // Exact match (highest priority)
        if (keyForMatching == inputLower) {
            score += 10000
        }

        // Starts with input (very high priority)
        if (keyForMatching.startsWith(inputLower)) {
            score += 5000
        }

        // Contains input as substring
        if (keyForMatching.contains(inputLower)) {
            score += 2000
            // Bonus for early position
            val position = keyForMatching.indexOf(inputLower)
            score += (100 - position).coerceAtLeast(0)
        }

        // Fuzzy match: all input words appear in key
        val keyWords = keyForMatching.split(Regex("[.\\-_]")).filter { it.isNotEmpty() }
        val allWordsMatch = inputKeyWords.all { inputWord ->
            keyWords.any { it.contains(inputWord) }
        }

        if (allWordsMatch) {
            score += 1000
            // Bonus if words are in the same order
            var lastIndex = -1
            var inOrder = true
            for (inputWord in inputKeyWords) {
                val index = keyWords.indexOfFirst { it.contains(inputWord) }
                if (index <= lastIndex) {
                    inOrder = false
                    break
                }
                lastIndex = index
            }
            if (inOrder) {
                score += 500
            }

            // Extra bonus if key starts with the first input word
            if (inputKeyWords.isNotEmpty() && keyWords.isNotEmpty()) {
                val firstInputWord = inputKeyWords.first()
                val firstKeyWord = keyWords.first()
                if (firstKeyWord.startsWith(firstInputWord)) {
                    score += 3000  // Strong bonus for matching the first segment
                } else if (firstKeyWord.contains(firstInputWord)) {
                    score += 1000  // Moderate bonus if first key word contains the input
                }
            }
        }

        // Acronym match (e.g., "un" matches "user.name")
        if (inputLower.length >= 2) {
            val acronym = keyWords.mapNotNull { it.firstOrNull() }.joinToString("")
            if (acronym.contains(inputLower)) {
                score += 300
            }
        }

        // Bonus for shorter keys (prefer specific over general)
        val lengthBonus = (200 - keyForMatching.length).coerceAtLeast(0)
        score += lengthBonus

        // Bonus if in the current namespace
        if (namespace != null && key.startsWith("$namespace.")) {
            score += 1500
        }

        // Add translation match score for display locale (if available)
        if (!translation.isNullOrBlank()) {
            score += calculateTranslationMatchScore(translation, inputLower, inputTranslationWords)
        }

        return score
    }

    private fun calculateTranslationMatchScore(
        translation: String,
        inputLower: String,
        inputWords: List<String>
    ): Int {
        if (inputLower.isBlank()) return 0

        val translationLower = translation.lowercase()
        var score = 0

        if (translationLower == inputLower) {
            score += 6000
        }

        if (translationLower.startsWith(inputLower)) {
            score += 3000
        }

        if (translationLower.contains(inputLower)) {
            score += 1500
            val position = translationLower.indexOf(inputLower)
            score += (80 - position).coerceAtLeast(0)
        }

        if (inputWords.isNotEmpty()) {
            val translationWords = translationLower
                .split(Regex("[\\s\\p{Punct}]+"))
                .filter { it.isNotEmpty() }
            val allWordsMatch = inputWords.all { inputWord ->
                translationWords.any { it.contains(inputWord) } || translationLower.contains(inputWord)
            }
            if (allWordsMatch) {
                score += 800
            }
        }

        return score
    }
}

private fun hasTranslationMatch(text: String, input: String): Boolean {
    if (text.isBlank() || input.isBlank()) return false
    val textLower = text.lowercase()
    val inputLower = input.lowercase()
    if (textLower.contains(inputLower)) return true
    val words = inputLower.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    return words.all { textLower.contains(it) }
}


/**
 * Custom PrefixMatcher that accepts all keys.
 * We handle fuzzy matching ourselves in rankKeysByMatch.
 */
private class I18nFuzzyPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {

    override fun prefixMatches(name: String): Boolean {
        // Accept all keys - our ranking logic handles relevance
        return true
    }

    override fun cloneWithPrefix(prefix: String): PrefixMatcher {
        return I18nFuzzyPrefixMatcher(prefix)
    }
}

/**
 * Custom weigher that sorts completion items by our calculated scores.
 * Higher score = better match = should appear first (lower weight).
 */
private class I18nKeyWeigher(private val keyScores: Map<String, Int>) : LookupElementWeigher("i18nKeyRelevance") {

    override fun weigh(element: LookupElement): Comparable<*> {
        // The lookupObject is the original key (set in LookupElementBuilder.create(key, displayKey))
        val key = element.`object` as? String ?: return Int.MAX_VALUE
        val score = keyScores[key] ?: 0
        // Return negative score because lower weight = higher priority in sorting
        return -score
    }
}
