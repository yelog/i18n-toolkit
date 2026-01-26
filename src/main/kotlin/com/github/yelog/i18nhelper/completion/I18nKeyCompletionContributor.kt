package com.github.yelog.i18nhelper.completion

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.github.yelog.i18nhelper.util.I18nFunctionResolver
import com.github.yelog.i18nhelper.util.I18nNamespaceResolver
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

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

        // Get the current input text (what user has typed so far)
        val currentText = parameters.position.text.removeSuffix("IntellijIdeaRulezzz").trim('"', '\'')
        logger.info("Current input text: '$currentText'")

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

        // Filter and rank keys based on fuzzy matching
        val rankedKeys = rankKeysByMatch(currentText, allKeys.toList(), namespace)
        logger.info("Ranked keys count: ${rankedKeys.size}, showing top ${rankedKeys.take(50).size}")

        // Get display locale for showing translations
        val settings = I18nSettingsState.getInstance(project)
        val displayLocale = settings.getDisplayLocaleOrNull()

        // Create completion items
        var addedCount = 0
        for ((key, score) in rankedKeys.take(50)) { // Limit to top 50 results
            val translations = cacheService.getAllTranslations(key)

            // Get the translation value for the display
            val translationValue = if (displayLocale != null) {
                translations[displayLocale]?.value
            } else {
                translations.values.firstOrNull()?.value
            }

            // Determine the key to show (remove namespace prefix if applicable)
            val displayKey = if (namespace != null && key.startsWith("$namespace.")) {
                key.removePrefix("$namespace.")
            } else {
                key
            }

            val lookupElement = LookupElementBuilder.create(displayKey)
                .withPresentableText(displayKey)
                .withTailText(" (${translations.size} locales)", true)
                .withTypeText(translationValue?.take(50) ?: "", true)
                .withIcon(com.intellij.icons.AllIcons.Nodes.Property)
                .withInsertHandler { insertContext, _ ->
                    // Insert the key inside quotes
                    val editor = insertContext.editor
                    val document = editor.document
                    val startOffset = insertContext.startOffset
                    val tailOffset = insertContext.tailOffset

                    // Replace the current text with the selected key
                    document.replaceString(startOffset, tailOffset, displayKey)
                    editor.caretModel.moveToOffset(startOffset + displayKey.length)
                }

            // Add with priority based on match score
            val prioritizedElement = PrioritizedLookupElement.withPriority(lookupElement, score.toDouble())
            result.addElement(prioritizedElement)
            addedCount++
        }
        logger.info("Successfully added $addedCount completion items")
    }

    /**
     * Rank keys by how well they match the input text.
     * Returns a list of (key, score) pairs sorted by score (higher is better).
     */
    private fun rankKeysByMatch(
        input: String,
        keys: List<String>,
        namespace: String?
    ): List<Pair<String, Int>> {
        if (input.isBlank()) {
            // If no input, show all keys sorted alphabetically
            return keys.map { it to 0 }.sortedBy { it.first }
        }

        val inputLower = input.lowercase()
        val inputWords = inputLower.split(Regex("[.\\-_\\s]+")).filter { it.isNotEmpty() }

        return keys.mapNotNull { key ->
            val score = calculateMatchScore(key, inputLower, inputWords, namespace)
            if (score > 0) key to score else null
        }.sortedByDescending { it.second }
    }

    /**
     * Calculate match score for a key.
     * Higher score = better match.
     */
    private fun calculateMatchScore(
        key: String,
        inputLower: String,
        inputWords: List<String>,
        namespace: String?
    ): Int {
        val keyLower = key.lowercase()
        var score = 0

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
        val allWordsMatch = inputWords.all { inputWord ->
            keyWords.any { it.contains(inputWord) }
        }

        if (allWordsMatch) {
            score += 1000
            // Bonus if words are in the same order
            var lastIndex = -1
            var inOrder = true
            for (inputWord in inputWords) {
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

        return score
    }
}
