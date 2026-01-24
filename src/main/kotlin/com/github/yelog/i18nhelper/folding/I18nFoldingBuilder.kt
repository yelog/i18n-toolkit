package com.github.yelog.i18nhelper.folding

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.settings.I18nDisplayMode
import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap

class I18nFoldingBuilder : FoldingBuilderEx() {

    companion object {
        private val fileProcessedOffsets = ConcurrentHashMap<String, MutableSet<Int>>()
        private val fileTimestamps = ConcurrentHashMap<String, Long>()

        private fun getOrCreateOffsetSet(filePath: String, documentTimestamp: Long): MutableSet<Int> {
            val lastTimestamp = fileTimestamps[filePath]
            if (lastTimestamp != documentTimestamp) {
                fileProcessedOffsets[filePath] = ConcurrentHashMap.newKeySet()
                fileTimestamps[filePath] = documentTimestamp
            }
            return fileProcessedOffsets.getOrPut(filePath) { ConcurrentHashMap.newKeySet() }
        }

        fun clearCache() {
            fileProcessedOffsets.clear()
            fileTimestamps.clear()
        }
    }

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val project = root.project
        val settings = I18nSettingsState.getInstance(project)
        if (settings.state.displayMode != I18nDisplayMode.TRANSLATION_ONLY) return emptyArray()

        val filePath = root.containingFile.originalFile.virtualFile?.path
            ?: root.containingFile.virtualFile?.path
            ?: root.containingFile.name
        val processedOffsets = getOrCreateOffsetSet(filePath, document.modificationStamp)

        val cacheService = I18nCacheService.getInstance(project)
        val locale = settings.getDisplayLocaleOrNull()
        val descriptors = mutableListOf<FoldingDescriptor>()

        val calls = PsiTreeUtil.findChildrenOfType(root, JSCallExpression::class.java)
        for (call in calls) {
            val methodExpr = call.methodExpression as? JSReferenceExpression ?: continue
            val methodName = methodExpr.referenceName ?: continue
            if (!i18nFunctions.contains(methodName)) continue

            val args = call.arguments
            if (args.isEmpty()) continue
            val firstArg = args[0] as? JSLiteralExpression ?: continue
            val key = firstArg.stringValue ?: continue
            val translation = cacheService.getTranslation(key, locale) ?: continue

            val offset = firstArg.textRange.endOffset
            if (!processedOffsets.add(offset)) continue

            // Only fold the key string literal, not the entire t() call
            descriptors.add(FoldingDescriptor(firstArg.node, firstArg.textRange))
        }

        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        val literal = node.psi as? JSLiteralExpression ?: return null
        val key = literal.stringValue ?: return null
        val settings = I18nSettingsState.getInstance(literal.project)
        val locale = settings.getDisplayLocaleOrNull()
        val translation = I18nCacheService.getInstance(literal.project).getTranslation(key, locale) ?: return null

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
