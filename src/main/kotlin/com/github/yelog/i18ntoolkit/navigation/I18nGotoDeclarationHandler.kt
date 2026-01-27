package com.github.yelog.i18ntoolkit.navigation

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/**
 * Handles Cmd+Click navigation for i18n keys, including folded regions and translation files
 */
class I18nGotoDeclarationHandler : GotoDeclarationHandler {

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null || editor == null) return null

        val project = sourceElement.project
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val virtualFile = psiFile.virtualFile ?: return null

        val cacheService = I18nCacheService.getInstance(project)

        // Check if we're in a translation file - handle cycling between locales
        if (I18nDirectoryScanner.isTranslationFile(virtualFile)) {
            return handleTranslationFileNavigation(sourceElement, cacheService, project)
        }

        // Find the i18n call at the current offset
        val (fullKey, partialKey) = findI18nKeyAtOffset(psiFile, offset) ?: return null

        // Try full key first, then fallback to partial key
        var entries = cacheService.getAllTranslations(fullKey)
        if (entries.isEmpty() && fullKey != partialKey) {
            entries = cacheService.getAllTranslations(partialKey)
        }

        if (entries.isEmpty()) return null

        // Deduplicate by file path and offset
        val seen = mutableSetOf<String>()
        val targets = entries.values.mapNotNull { entry ->
            val uniqueKey = "${entry.file.path}:${entry.offset}"
            if (seen.contains(uniqueKey)) {
                null
            } else {
                seen.add(uniqueKey)
                findPsiElement(project, entry)?.let { psiElement ->
                    I18nNavigationTarget(psiElement, entry, project.basePath)
                }
            }
        }

        return if (targets.isNotEmpty()) targets.toTypedArray() else null
    }

    /**
     * Handle Cmd+Click in translation files - cycle to next locale
     */
    private fun handleTranslationFileNavigation(
        sourceElement: PsiElement,
        cacheService: I18nCacheService,
        project: com.intellij.openapi.project.Project
    ): Array<PsiElement>? {
        // Find the property element (JSON or JS/TS)
        val property = findPropertyElement(sourceElement) ?: return null
        val virtualFile = sourceElement.containingFile?.virtualFile ?: return null

        val translationFile = cacheService.getTranslationFile(virtualFile) ?: return null
        val fullKey = buildFullKey(property, translationFile.keyPrefix)

        // Get all translations for this key
        val allTranslations = cacheService.getAllTranslations(fullKey)
        if (allTranslations.size <= 1) return null

        // Sort locales for consistent ordering
        val sortedLocales = allTranslations.keys.sorted()
        val currentLocale = translationFile.locale

        // Find next locale in cycle
        val currentIndex = sortedLocales.indexOf(currentLocale)
        val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % sortedLocales.size else 0
        val nextLocale = sortedLocales[nextIndex]

        val nextEntry = allTranslations[nextLocale] ?: return null

        val psiElement = findPsiElement(project, nextEntry) ?: return null
        return arrayOf(I18nNavigationTarget(psiElement, nextEntry, project.basePath))
    }

    private fun findPropertyElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is JsonProperty || current is JSProperty) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun buildFullKey(property: PsiElement, prefix: String): String {
        val keyParts = mutableListOf<String>()
        var current: PsiElement? = property

        while (current != null) {
            when (current) {
                is JsonProperty -> {
                    keyParts.add(0, current.name)
                    current = current.parent?.parent
                }
                is JSProperty -> {
                    current.name?.let { keyParts.add(0, it) }
                    current = current.parent?.parent
                }
                else -> current = current.parent
            }
        }

        val key = keyParts.joinToString(".")
        return if (prefix.isEmpty()) key else "$prefix$key"
    }

    /**
     * Returns a pair of (fullKey, partialKey) where fullKey includes namespace prefix
     */
    private fun findI18nKeyAtOffset(psiFile: PsiElement, offset: Int): Pair<String, String>? {
        // Try to find JSLiteralExpression at offset
        var element = psiFile.findElementAt(offset)

        // Walk up to find JSCallExpression
        while (element != null) {
            if (element is JSCallExpression) {
                return extractI18nKeys(element)
            }
            if (element is JSLiteralExpression) {
                val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
                if (call != null) {
                    return extractI18nKeys(call)
                }
            }
            element = element.parent
        }

        // Also try searching in a range around the offset (for folded regions)
        val searchRange = maxOf(0, offset - 50)..minOf(psiFile.textLength, offset + 50)
        val calls = PsiTreeUtil.findChildrenOfType(psiFile, JSCallExpression::class.java)
        for (call in calls) {
            if (call.textRange.startOffset in searchRange || call.textRange.endOffset in searchRange) {
                val keys = extractI18nKeys(call)
                if (keys != null && call.textRange.contains(offset)) {
                    return keys
                }
            }
        }

        return null
    }

    /**
     * Returns a pair of (fullKey, partialKey) where fullKey includes namespace prefix
     */
    private fun extractI18nKeys(call: JSCallExpression): Pair<String, String>? {
        val methodExpr = call.methodExpression as? JSReferenceExpression ?: return null
        val methodName = methodExpr.referenceName ?: return null

        if (!i18nFunctions.contains(methodName)) return null

        val args = call.arguments
        if (args.isEmpty()) return null

        val firstArg = args[0] as? JSLiteralExpression ?: return null
        val partialKey = firstArg.stringValue ?: return null
        val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)
        return Pair(fullKey, partialKey)
    }

    private fun findPsiElement(project: com.intellij.openapi.project.Project, entry: TranslationEntry): PsiElement? {
        val psiFile = PsiManager.getInstance(project).findFile(entry.file) ?: return null
        return psiFile.findElementAt(entry.offset)
    }
}

/**
 * Navigation target element with custom presentation
 */
class I18nNavigationTarget(
    private val delegate: PsiElement,
    private val entry: TranslationEntry,
    private val projectBasePath: String?
) : FakePsiElement() {

    override fun getParent(): PsiElement = delegate.parent

    override fun getNavigationElement(): PsiElement = delegate

    override fun navigate(requestFocus: Boolean) {
        val descriptor = OpenFileDescriptor(delegate.project, entry.file, entry.offset)
        descriptor.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String {
                val lineContent = getLineContent()
                return "[${entry.locale}] $lineContent"
            }

            override fun getLocationString(): String {
                val relativePath = getDistinctivePath()
                val lineNumber = getLineNumber()
                return "$relativePath:$lineNumber"
            }

            override fun getIcon(unused: Boolean): Icon? = null
        }
    }

    private fun getDistinctivePath(): String {
        val filePath = entry.file.path
        val basePath = projectBasePath ?: return entry.file.name

        val relativePath = if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length).trimStart('/')
        } else {
            filePath
        }

        val i18nDirs = listOf("locales", "locale", "i18n", "lang", "langs", "messages", "translations")
        val parts = relativePath.split("/")

        for (i in parts.indices) {
            if (i18nDirs.contains(parts[i].lowercase())) {
                val remaining = parts.subList(i, parts.size)
                return if (remaining.size > 3) {
                    remaining.takeLast(3).joinToString("/")
                } else {
                    remaining.joinToString("/")
                }
            }
        }

        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString("/")
        } else {
            entry.file.name
        }
    }

    private fun getLineNumber(): Int {
        val psiFile = PsiManager.getInstance(delegate.project).findFile(entry.file) ?: return 0
        val document: Document = PsiDocumentManager.getInstance(delegate.project).getDocument(psiFile) ?: return 0
        return document.getLineNumber(entry.offset) + 1
    }

    private fun getLineContent(): String {
        val psiFile = PsiManager.getInstance(delegate.project).findFile(entry.file) ?: return entry.value
        val document: Document = PsiDocumentManager.getInstance(delegate.project).getDocument(psiFile) ?: return entry.value
        val lineNumber = document.getLineNumber(entry.offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(TextRange(lineStart, lineEnd)).trim()
        return if (lineText.length > 60) lineText.take(57) + "..." else lineText
    }
}
