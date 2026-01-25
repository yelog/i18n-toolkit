package com.github.yelog.i18nhelper.action

import java.awt.datatransfer.StringSelection
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.codeInsight.hint.HintManager
import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nKeyCandidate
import com.github.yelog.i18nhelper.util.I18nKeyExtractor

class I18nCopyKeyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val cacheService = I18nCacheService.getInstance(project)
        cacheService.initialize()

        val keyCandidate = I18nKeyExtractor.findKeyAtOffset(psiFile, editor.caretModel.offset, cacheService)
        val key = resolveDisplayKey(keyCandidate, cacheService)
        if (key.isNullOrBlank()) {
            showHint(editor, "No i18n key found at caret")
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(key))
        showHint(editor, "Copied i18n key: $key")
    }

    private fun resolveDisplayKey(
        keyCandidate: I18nKeyCandidate?,
        cacheService: I18nCacheService
    ): String? {
        if (keyCandidate == null) return null
        if (cacheService.getAllTranslations(keyCandidate.fullKey).isNotEmpty()) {
            return keyCandidate.fullKey
        }
        if (keyCandidate.fullKey != keyCandidate.partialKey &&
            cacheService.getAllTranslations(keyCandidate.partialKey).isNotEmpty()
        ) {
            return keyCandidate.partialKey
        }
        return keyCandidate.fullKey
    }

    private fun showHint(editor: Editor, message: String) {
        HintManager.getInstance().showInformationHint(editor, message)
    }
}
