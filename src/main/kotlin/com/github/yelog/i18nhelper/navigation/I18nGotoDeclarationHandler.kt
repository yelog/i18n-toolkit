package com.github.yelog.i18nhelper.navigation

import com.github.yelog.i18nhelper.model.TranslationEntry
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
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
 * Handles Cmd+Click navigation for i18n keys, including folded regions
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

        // Find the i18n call at the current offset
        val i18nKey = findI18nKeyAtOffset(psiFile, offset) ?: return null

        val cacheService = I18nCacheService.getInstance(project)
        val entries = cacheService.getAllTranslations(i18nKey)

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

    private fun findI18nKeyAtOffset(psiFile: PsiElement, offset: Int): String? {
        // Try to find JSLiteralExpression at offset
        var element = psiFile.findElementAt(offset)

        // Walk up to find JSCallExpression
        while (element != null) {
            if (element is JSCallExpression) {
                return extractI18nKey(element)
            }
            if (element is JSLiteralExpression) {
                val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
                if (call != null) {
                    return extractI18nKey(call)
                }
            }
            element = element.parent
        }

        // Also try searching in a range around the offset (for folded regions)
        val searchRange = maxOf(0, offset - 50)..minOf(psiFile.textLength, offset + 50)
        val calls = PsiTreeUtil.findChildrenOfType(psiFile, JSCallExpression::class.java)
        for (call in calls) {
            if (call.textRange.startOffset in searchRange || call.textRange.endOffset in searchRange) {
                val key = extractI18nKey(call)
                if (key != null && call.textRange.contains(offset)) {
                    return key
                }
            }
        }

        return null
    }

    private fun extractI18nKey(call: JSCallExpression): String? {
        val methodExpr = call.methodExpression as? JSReferenceExpression ?: return null
        val methodName = methodExpr.referenceName ?: return null

        if (!i18nFunctions.contains(methodName)) return null

        val args = call.arguments
        if (args.isEmpty()) return null

        val firstArg = args[0] as? JSLiteralExpression ?: return null
        return firstArg.stringValue
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
