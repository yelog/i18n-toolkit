package com.github.yelog.i18ntoolkit.spring

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.util.PsiTreeUtil

/**
 * Identifies Spring i18n patterns in Java code and extracts translation keys.
 *
 * Supported patterns:
 * 1. messageSource.getMessage("key", args, locale)
 * 2. Custom utility methods like MessageUtils.get("key"), I18nUtil.msg("key")
 * 3. Validation annotations: @NotBlank(message = "{key}")
 */
object SpringMessagePatternMatcher {

    /**
     * Default Spring i18n method names to match.
     */
    private val MESSAGE_SOURCE_METHODS = setOf("getMessage", "getMsg")

    /**
     * Common custom utility method names for i18n in Spring projects.
     */
    private val CUSTOM_I18N_METHODS = setOf(
        "get", "msg", "message", "translate", "t",
        "getMessage", "getMsg", "getLocalizedMessage"
    )

    /**
     * Common class names for i18n utility classes.
     */
    private val I18N_UTILITY_CLASSES = setOf(
        "MessageUtils", "I18nUtil", "I18nUtils", "I18nHelper",
        "MessageHelper", "LocaleUtils", "Messages", "Msg"
    )

    /**
     * Result of pattern matching: the extracted key and the PSI element containing it.
     */
    data class I18nKeyMatch(
        val key: String,
        val keyElement: PsiLiteralExpression,
        val matchType: MatchType
    )

    enum class MatchType {
        MESSAGE_SOURCE,
        CUSTOM_UTILITY,
        VALIDATION_ANNOTATION
    }

    /**
     * Try to extract an i18n key from a Java PSI element.
     * The element should be a string literal that is:
     * - The first argument of a getMessage() call
     * - The first argument of a custom i18n utility method
     * - The message attribute of a validation annotation (e.g., @NotBlank(message = "{key}"))
     */
    fun extractKey(element: PsiElement): I18nKeyMatch? {
        if (element !is PsiLiteralExpression) return null
        val value = element.value as? String ?: return null
        if (value.isBlank()) return null

        // Check if inside a method call expression
        val methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
        if (methodCall != null) {
            return matchMethodCall(element, value, methodCall)
        }

        // Check if inside an annotation
        val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java)
        if (annotation != null) {
            return matchAnnotation(element, value, annotation)
        }

        return null
    }

    /**
     * Check if a method call expression is a Spring i18n call and extract the key.
     */
    fun matchMethodCall(
        literal: PsiLiteralExpression,
        value: String,
        methodCall: PsiMethodCallExpression
    ): I18nKeyMatch? {
        val methodExpr = methodCall.methodExpression
        val methodName = methodExpr.referenceName ?: return null

        // Check: is this literal the first argument?
        val args = methodCall.argumentList.expressions
        if (args.isEmpty() || args[0] !== literal) return null

        // Pattern 1: messageSource.getMessage("key", ...)
        if (MESSAGE_SOURCE_METHODS.contains(methodName)) {
            return I18nKeyMatch(value, literal, MatchType.MESSAGE_SOURCE)
        }

        // Pattern 2: MessageUtils.get("key") or similar utility calls
        if (CUSTOM_I18N_METHODS.contains(methodName)) {
            val qualifier = methodExpr.qualifierExpression
            val qualifierText = qualifier?.text
            if (qualifierText != null && I18N_UTILITY_CLASSES.any { qualifierText.contains(it) }) {
                return I18nKeyMatch(value, literal, MatchType.CUSTOM_UTILITY)
            }
            // Also match instance method calls like messageSource.getMessage
            // where qualifierText could be a variable name
            if (qualifierText != null && qualifierText.lowercase().contains("message")) {
                return I18nKeyMatch(value, literal, MatchType.MESSAGE_SOURCE)
            }
        }

        return null
    }

    /**
     * Match validation annotation patterns: @NotBlank(message = "{key}")
     * The key is extracted without the surrounding { }.
     */
    fun matchAnnotation(
        literal: PsiLiteralExpression,
        value: String,
        annotation: PsiAnnotation
    ): I18nKeyMatch? {
        // Check if this literal is the "message" attribute value
        val nameValuePair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java)
        val attrName = nameValuePair?.name ?: nameValuePair?.attributeName
        if (attrName != "message") return null

        // Validation annotation message format: "{key}"
        if (value.startsWith("{") && value.endsWith("}")) {
            val key = value.substring(1, value.length - 1)
            if (key.isNotBlank()) {
                return I18nKeyMatch(key, literal, MatchType.VALIDATION_ANNOTATION)
            }
        }

        return null
    }

    /**
     * Check if a PsiMethodCallExpression is a Spring i18n method call.
     */
    fun isSpringI18nCall(methodCall: PsiMethodCallExpression): Boolean {
        val methodName = methodCall.methodExpression.referenceName ?: return false

        if (MESSAGE_SOURCE_METHODS.contains(methodName)) return true

        if (CUSTOM_I18N_METHODS.contains(methodName)) {
            val qualifier = methodCall.methodExpression.qualifierExpression?.text
            if (qualifier != null) {
                return I18N_UTILITY_CLASSES.any { qualifier.contains(it) }
                        || qualifier.lowercase().contains("message")
            }
        }

        return false
    }
}
