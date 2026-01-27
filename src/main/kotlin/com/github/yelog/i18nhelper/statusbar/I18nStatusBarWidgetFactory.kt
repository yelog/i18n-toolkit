package com.github.yelog.i18nhelper.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Factory for creating I18n status bar widget
 */
class I18nStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = I18nStatusBarWidget.ID

    override fun getDisplayName(): String = "I18n Display Language"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return I18nStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        // Widget disposal is handled by the widget itself
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
