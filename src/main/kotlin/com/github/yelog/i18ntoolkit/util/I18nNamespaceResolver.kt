package com.github.yelog.i18ntoolkit.util

import com.github.yelog.i18ntoolkit.model.I18nFramework
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil

/**
 * Utility to resolve i18n namespace from useTranslation hook
 * Supports patterns like:
 * - const { t } = useTranslation('namespace')
 * - const { t } = useTranslation(['ns1', 'ns2'])
 * - const { t } = useI18n({ messages: ... })
 *
 * Also supports i18next inline namespace syntax: t('namespace:key')
 * (i18next default nsSeparator is ':').
 */
object I18nNamespaceResolver {

    /**
     * i18next default inline namespace separator.
     */
    const val NS_SEPARATOR = ':'

    private val translationHooks = setOf(
        "useTranslation",  // react-i18next
        "useI18n",         // vue-i18n
        "useTranslations"  // next-intl
    )

    /**
     * Frameworks that use ':' as an inline namespace separator (i18next family).
     * UNKNOWN is included (best-effort) so monorepos where framework detection
     * falls back to UNKNOWN still work; lookup fallback to the raw key keeps
     * this safe for non-i18next projects.
     */
    private val inlineNsFrameworks = setOf(
        I18nFramework.REACT_I18NEXT,
        I18nFramework.I18NEXT,
        I18nFramework.UNKNOWN
    )

    /**
     * Find the namespace for a t() call by looking at the useTranslation hook
     * Returns the namespace prefix (with trailing dot) or empty string if not found
     */
    fun resolveNamespace(tCallExpression: JSCallExpression): String {
        // Find the function/component containing this t() call
        val containingFunction = PsiTreeUtil.getParentOfType(
            tCallExpression,
            JSFunction::class.java,
            JSFunctionExpression::class.java
        ) ?: return ""

        // Search for useTranslation call in the same scope
        val namespace = findUseTranslationNamespace(containingFunction)
        return if (namespace.isNotEmpty()) "$namespace." else ""
    }

    /**
     * Get the full key by prepending namespace if applicable.
     *
     * i18next inline namespace ('ns:key') takes priority over the
     * useTranslation hook (matching i18next's resolution semantics) and is
     * normalized to the plugin's dotted cache form ('ns.key').
     */
    fun getFullKey(tCallExpression: JSCallExpression, key: String): String {
        if (shouldParseInlineNamespace(tCallExpression.project)) {
            extractInlineNamespace(key)?.let { (ns, realKey) ->
                return "$ns.$realKey"
            }
        }
        val namespace = resolveNamespace(tCallExpression)
        return "$namespace$key"
    }

    /**
     * Parse an i18next inline namespace key like 'account_bill:bill_record'
     * into (namespace, realKey). Returns null when there is no separator or
     * the namespace/key part is blank. Only the first separator is considered.
     */
    fun extractInlineNamespace(key: String): Pair<String, String>? {
        val idx = key.indexOf(NS_SEPARATOR)
        if (idx <= 0 || idx >= key.length - 1) return null
        val ns = key.substring(0, idx)
        val realKey = key.substring(idx + 1)
        if (ns.isBlank() || realKey.isBlank()) return null
        return ns to realKey
    }

    /**
     * Detect an inline namespace already typed by the user (e.g. 'account_bill:'
     * or 'account_bill:bill') for completion context. Returns the namespace or null.
     */
    fun detectInlineNamespaceInput(input: String): String? {
        val idx = input.indexOf(NS_SEPARATOR)
        if (idx <= 0) return null
        val ns = input.substring(0, idx)
        return ns.takeIf { it.isNotBlank() }
    }

    private fun shouldParseInlineNamespace(project: Project): Boolean {
        return I18nCacheService.getInstance(project).getFramework() in inlineNsFrameworks
    }

    private fun findUseTranslationNamespace(scope: PsiElement): String {
        var namespace = ""

        scope.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (namespace.isNotEmpty()) return // Already found

                if (element is JSCallExpression) {
                    val methodExpr = element.methodExpression as? JSReferenceExpression
                    val methodName = methodExpr?.referenceName

                    if (methodName != null && translationHooks.contains(methodName)) {
                        namespace = extractNamespaceFromHook(element)
                    }
                }

                super.visitElement(element)
            }
        })

        return namespace
    }

    private fun extractNamespaceFromHook(hookCall: JSCallExpression): String {
        val args = hookCall.arguments
        if (args.isEmpty()) return ""

        val firstArg = args[0]

        return when (firstArg) {
            // useTranslation('namespace')
            is JSLiteralExpression -> firstArg.stringValue ?: ""

            // useTranslation(['ns1', 'ns2']) - use first namespace
            is JSArrayLiteralExpression -> {
                val firstElement = firstArg.expressions.firstOrNull()
                (firstElement as? JSLiteralExpression)?.stringValue ?: ""
            }

            // useI18n({ messages: ... }) or other object patterns
            is JSObjectLiteralExpression -> {
                // Look for 'namespace' or 'ns' property
                val nsProp = firstArg.findProperty("namespace")
                    ?: firstArg.findProperty("ns")
                val value = nsProp?.value as? JSLiteralExpression
                value?.stringValue ?: ""
            }

            else -> ""
        }
    }

    /**
     * Check if a key might be a partial key (used with namespace)
     * by checking if it doesn't contain dots or is a simple identifier
     */
    fun mightBePartialKey(key: String): Boolean {
        return !key.contains('.') || key.split('.').size <= 2
    }

    /**
     * Generate possible full keys for a partial key
     * Used for searching usages
     */
    fun generatePossibleKeys(partialKey: String, knownNamespaces: Set<String>): List<String> {
        val keys = mutableListOf(partialKey)
        knownNamespaces.forEach { ns ->
            keys.add("$ns.$partialKey")
        }
        return keys
    }
}
