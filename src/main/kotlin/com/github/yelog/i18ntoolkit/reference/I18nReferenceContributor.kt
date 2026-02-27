package com.github.yelog.i18ntoolkit.reference

import com.github.yelog.i18ntoolkit.I18nConstants

import com.github.yelog.i18ntoolkit.model.TranslationEntry
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import javax.swing.Icon

class I18nReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            I18nReferenceProvider()
        )
    }
}

class I18nReferenceProvider : PsiReferenceProvider() {

    companion object {
        // Track processed elements to avoid duplicates from multiple language registrations
        private val processedElements = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<PsiElement, Boolean>()
        )
    }

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val literal = element as? JSLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        val stringValue = literal.stringValue ?: return PsiReference.EMPTY_ARRAY

        val callExpr = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java)
        if (!isI18nFunctionArgument(literal, callExpr)) {
            return PsiReference.EMPTY_ARRAY
        }

        // Deduplicate: avoid processing the same element multiple times
        synchronized(processedElements) {
            if (processedElements.contains(element)) {
                return PsiReference.EMPTY_ARRAY
            }
            processedElements.add(element)
        }

        // Resolve full key including namespace from useTranslation hook
        val fullKey = if (callExpr != null) {
            I18nNamespaceResolver.getFullKey(callExpr, stringValue)
        } else {
            stringValue
        }

        val textRange = TextRange(1, stringValue.length + 1)
        return arrayOf(I18nKeyReference(literal, textRange, fullKey, stringValue))
    }

    private fun isI18nFunctionArgument(literal: JSLiteralExpression, callExpr: JSCallExpression?): Boolean {
        callExpr ?: return false
        val methodExpr = callExpr.methodExpression as? JSReferenceExpression ?: return false
        val methodName = methodExpr.referenceName ?: return false

        if (!i18nFunctions.contains(methodName)) return false

        val args = callExpr.arguments
        return args.isNotEmpty() && args[0] == literal
    }
}

class I18nKeyReference(
    element: PsiElement,
    textRange: TextRange,
    private val fullKey: String,
    private val partialKey: String = fullKey
) : PsiReferenceBase<PsiElement>(element, textRange), PsiPolyVariantReference {

    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        return if (results.size == 1) results[0].element else null
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        // Try full key first (with namespace), then fallback to partial key
        var entries = cacheService.getAllTranslations(fullKey)
        if (entries.isEmpty() && fullKey != partialKey) {
            entries = cacheService.getAllTranslations(partialKey)
        }

        // Deduplicate by file path and offset
        val seen = mutableSetOf<String>()
        return entries.values.mapNotNull { entry ->
            val uniqueKey = "${entry.file.path}:${entry.offset}"
            if (seen.contains(uniqueKey)) {
                null
            } else {
                seen.add(uniqueKey)
                findPsiElement(entry)?.let { psiElement ->
                    val wrapper = I18nNavigationElement(psiElement, entry, project.basePath)
                    PsiElementResolveResult(wrapper)
                }
            }
        }.toTypedArray()
    }

    private fun findPsiElement(entry: TranslationEntry): PsiElement? {
        val psiFile = PsiManager.getInstance(element.project).findFile(entry.file) ?: return null
        return psiFile.findElementAt(entry.offset)
    }

    override fun getVariants(): Array<Any> {
        val project = element.project
        val cacheService = I18nCacheService.getInstance(project)
        return cacheService.getAllKeys().toTypedArray()
    }
}

/**
 * Wrapper element that provides better presentation for navigation popup
 */
class I18nNavigationElement(
    private val delegate: PsiElement,
    private val entry: TranslationEntry,
    private val projectBasePath: String?
) : FakePsiElement() {

    override fun getParent(): PsiElement = delegate.parent

    override fun getNavigationElement(): PsiElement = delegate

    override fun getContainingFile() = delegate.containingFile

    override fun getTextOffset(): Int = delegate.textOffset

    override fun navigate(requestFocus: Boolean) {
        // Use OpenFileDescriptor for reliable navigation
        val descriptor = OpenFileDescriptor(delegate.project, entry.file, entry.offset)
        descriptor.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun isEquivalentTo(another: PsiElement?): Boolean {
        val other = if (another is I18nNavigationElement) another.delegate else another
        return delegate.isEquivalentTo(other)
    }

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

    /**
     * Get a distinctive path that shows the locale folder and filename
     * e.g., "en_US/workshop.ts" or "lang/zh_CN/workshop.ts"
     */
    private fun getDistinctivePath(): String {
        val filePath = entry.file.path
        val basePath = projectBasePath ?: return entry.file.name

        // Get path relative to project
        val relativePath = if (filePath.startsWith(basePath)) {
            filePath.substring(basePath.length).trimStart('/')
        } else {
            filePath
        }

        // Find the i18n directory part and keep from there
        val i18nDirs = listOf("locales", "locale", "i18n", "lang", "langs", "messages", "translations")
        val parts = relativePath.split("/")

        for (i in parts.indices) {
            if (i18nDirs.contains(parts[i].lowercase())) {
                // Return from i18n dir to end, but skip the i18n dir itself if too long
                val remaining = parts.subList(i, parts.size)
                return if (remaining.size > 3) {
                    // If path is too long, show last 2-3 meaningful parts
                    remaining.takeLast(3).joinToString("/")
                } else {
                    remaining.joinToString("/")
                }
            }
        }

        // Fallback: show last 2 parts (parent folder + filename)
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
        return if (lineText.length > I18nConstants.Display.LINE_CONTENT_MAX_LENGTH) lineText.take(I18nConstants.Display.LINE_CONTENT_MAX_LENGTH - I18nConstants.Display.TRUNCATION_SUFFIX.length) + I18nConstants.Display.TRUNCATION_SUFFIX else lineText
    }
}
