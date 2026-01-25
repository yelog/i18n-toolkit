package com.github.yelog.i18nhelper.folding

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nDisplayMode
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.github.yelog.i18nhelper.util.I18nNamespaceResolver
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class I18nFoldingBuilder : FoldingBuilderEx() {

    companion object {
        @Volatile
        private var settingsVersion = 0L

        fun clearCache() {
            // Increment version to signal settings change
            settingsVersion++
        }
    }

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val project = root.project
        val settings = I18nSettingsState.getInstance(project)
        if (settings.state.displayMode != I18nDisplayMode.TRANSLATION_ONLY) return emptyArray()

        val cacheService = I18nCacheService.getInstance(project)
        val locale = settings.getDisplayLocaleOrNull()
        val descriptors = mutableListOf<FoldingDescriptor>()
        // Use local set for deduplication within this build pass only
        val processedOffsets = mutableSetOf<Int>()

        val calls = PsiTreeUtil.findChildrenOfType(root, JSCallExpression::class.java)
        for (call in calls) {
            val methodExpr = call.methodExpression as? JSReferenceExpression ?: continue
            val methodName = methodExpr.referenceName ?: continue
            if (!i18nFunctions.contains(methodName)) continue

            val args = call.arguments
            if (args.isEmpty()) continue
            val firstArg = args[0] as? JSLiteralExpression ?: continue
            val partialKey = firstArg.stringValue ?: continue
            // Resolve full key including namespace from useTranslation hook
            val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)
            val translation = cacheService.getTranslation(fullKey, locale)
                ?: cacheService.getTranslation(partialKey, locale)
                ?: continue

            val offset = firstArg.textRange.endOffset
            if (!processedOffsets.add(offset)) continue

            // Only fold the key string literal, not the entire t() call
            descriptors.add(FoldingDescriptor(firstArg.node, firstArg.textRange))
        }

        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        val literal = node.psi as? JSLiteralExpression ?: return null
        val partialKey = literal.stringValue ?: return null

        // Find parent call expression to resolve namespace
        val callExpr = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java)
        val fullKey = if (callExpr != null) {
            I18nNamespaceResolver.getFullKey(callExpr, partialKey)
        } else {
            partialKey
        }

        val settings = I18nSettingsState.getInstance(literal.project)
        val locale = settings.getDisplayLocaleOrNull()
        val cacheService = I18nCacheService.getInstance(literal.project)
        val translation = cacheService.getTranslation(fullKey, locale)
            ?: cacheService.getTranslation(partialKey, locale)
            ?: return null

        // Keep the quotes and replace only the content
        val quote = literal.text.firstOrNull() ?: '"'
        return "$quote${truncateText(translation.value, 50)}$quote"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        val settings = I18nSettingsState.getInstance(node.psi.project)
        return settings.state.displayMode == I18nDisplayMode.TRANSLATION_ONLY
    }

    private fun truncateText(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 3) + "..."
        } else {
            text
        }
    }
}
