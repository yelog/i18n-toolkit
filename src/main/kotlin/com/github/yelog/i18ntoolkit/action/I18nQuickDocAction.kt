package com.github.yelog.i18ntoolkit.action

import com.github.yelog.i18ntoolkit.popup.I18nTranslationEditPopup
import com.github.yelog.i18ntoolkit.scanner.I18nDirectoryScanner
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.settings.I18nSettingsState
import com.github.yelog.i18ntoolkit.util.I18nKeyExtractor
import com.github.yelog.i18ntoolkit.util.I18nNamespaceResolver
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wraps the built-in QuickJavaDoc action. When the caret is on an i18n key,
 * shows the editable translation popup instead of the standard documentation panel.
 * Otherwise delegates to the original QuickJavaDoc action.
 */
class I18nQuickDocAction(val originalAction: AnAction) : AnAction() {

    init {
        copyFrom(originalAction)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (project != null && editor != null && psiFile != null) {
            val offset = editor.caretModel.offset
            val cacheService = I18nCacheService.getInstance(project)

            // Only show edit popup when caret is inside a string literal (including quotes)
            // or inside a translation file. On `t` or other code, delegate to default behavior.
            val candidate = ReadAction.compute<com.github.yelog.i18ntoolkit.util.I18nKeyCandidate?, RuntimeException> {
                val virtualFile = psiFile.virtualFile
                if (virtualFile != null && I18nDirectoryScanner.isTranslationFile(virtualFile)) {
                    return@compute I18nKeyExtractor.findKeyAtOffset(psiFile, offset, cacheService)
                }

                // Check in host file first
                val elementAtCaret = psiFile.findElementAt(offset)
                val literal = PsiTreeUtil.getParentOfType(elementAtCaret, JSLiteralExpression::class.java, false)
                if (literal != null && literal.isStringLiteral && literal.textRange.containsOffset(offset)) {
                    return@compute extractKeyFromLiteral(literal)
                }

                // Check in injected language fragments (e.g., Vue templates)
                val injectedManager = InjectedLanguageManager.getInstance(project)
                val injectedElement = injectedManager.findInjectedElementAt(psiFile, offset)
                if (injectedElement != null) {
                    val injectedLiteral = PsiTreeUtil.getParentOfType(injectedElement, JSLiteralExpression::class.java, false)
                    if (injectedLiteral != null && injectedLiteral.isStringLiteral) {
                        return@compute extractKeyFromLiteral(injectedLiteral)
                    }
                }

                null
            }

            if (candidate != null) {
                val fullKey = candidate.fullKey
                val partialKey = candidate.partialKey

                var translations = cacheService.getAllTranslations(fullKey)
                val displayKey = if (translations.isNotEmpty()) {
                    fullKey
                } else if (fullKey != partialKey) {
                    translations = cacheService.getAllTranslations(partialKey)
                    partialKey
                } else {
                    fullKey
                }

                if (translations.isNotEmpty()) {
                    val settings = I18nSettingsState.getInstance(project)
                    val displayLocale = settings.getDisplayLocaleOrNull()
                    val allLocales = cacheService.getAvailableLocales()
                    I18nTranslationEditPopup.show(project, editor, displayKey, translations, displayLocale, allLocales)
                    return
                }
            }
        }

        // Not an i18n key — delegate to original Quick Documentation
        originalAction.actionPerformed(e)
    }

    override fun update(e: AnActionEvent) {
        originalAction.update(e)
    }

    override fun getActionUpdateThread() = originalAction.actionUpdateThread

    private val i18nFunctions = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    private fun extractKeyFromLiteral(literal: JSLiteralExpression): com.github.yelog.i18ntoolkit.util.I18nKeyCandidate? {
        val call = PsiTreeUtil.getParentOfType(literal, JSCallExpression::class.java) ?: return null
        val methodExpr = call.methodExpression as? JSReferenceExpression ?: return null
        val methodName = methodExpr.referenceName ?: return null
        if (!i18nFunctions.contains(methodName)) return null

        val partialKey = literal.stringValue ?: return null
        val fullKey = I18nNamespaceResolver.getFullKey(call, partialKey)
        return com.github.yelog.i18ntoolkit.util.I18nKeyCandidate(fullKey, partialKey)
    }
}
