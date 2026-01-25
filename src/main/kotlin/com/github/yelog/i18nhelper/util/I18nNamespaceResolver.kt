package com.github.yelog.i18nhelper.util

import com.intellij.lang.javascript.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Utility to resolve i18n namespace from useTranslation hook
 * Supports patterns like:
 * - const { t } = useTranslation('namespace')
 * - const { t } = useTranslation(['ns1', 'ns2'])
 * - const { t } = useI18n({ messages: ... })
 */
object I18nNamespaceResolver {

    private val translationHooks = setOf(
        "useTranslation",  // react-i18next
        "useI18n",         // vue-i18n
        "useTranslations"  // next-intl
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
     * Get the full key by prepending namespace if applicable
     */
    fun getFullKey(tCallExpression: JSCallExpression, key: String): String {
        val namespace = resolveNamespace(tCallExpression)
        return "$namespace$key"
    }

    private fun findUseTranslationNamespace(scope: PsiElement): String {
        var namespace = ""

        scope.accept(object : JSRecursiveElementVisitor() {
            override fun visitJSCallExpression(node: JSCallExpression) {
                if (namespace.isNotEmpty()) return // Already found

                val methodExpr = node.methodExpression as? JSReferenceExpression
                val methodName = methodExpr?.referenceName

                if (methodName != null && translationHooks.contains(methodName)) {
                    namespace = extractNamespaceFromHook(node)
                }

                super.visitJSCallExpression(node)
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
