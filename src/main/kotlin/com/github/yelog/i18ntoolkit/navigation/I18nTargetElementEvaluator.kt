package com.github.yelog.i18ntoolkit.navigation

import com.github.yelog.i18ntoolkit.reference.I18nKeyReference
import com.intellij.codeInsight.TargetElementEvaluator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

class I18nTargetElementEvaluator : TargetElementEvaluator {

    override fun includeSelfInGotoImplementation(element: PsiElement): Boolean = true

    override fun getElementByReference(ref: PsiReference, flags: Int): PsiElement? {
        return if (ref is I18nKeyReference) ref.element else null
    }
}
