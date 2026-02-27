package com.github.yelog.i18ntoolkit.action

import com.github.yelog.i18ntoolkit.service.I18nTranslationReporter
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Action to generate translation coverage reports.
 * Creates both HTML and CSV reports showing translation statistics.
 */
class I18nGenerateReportAction : AnAction(
    "Generate i18n Translation Report",
    "Generate HTML and CSV reports showing translation coverage and missing keys",
    com.intellij.icons.AllIcons.Actions.Profile
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        thisLogger().info("I18n Toolkit: Generating translation reports...")
        
        val config = I18nTranslationReporter.ReportConfig(
            outputDir = "i18n-reports",
            includeMissingDetails = true,
            includeOrphanedKeys = true
        )
        
        // Generate HTML report
        val htmlReport = I18nTranslationReporter.generateHtmlReport(project, config)
        val csvReport = I18nTranslationReporter.generateCsvReport(project, config)
        
        if (htmlReport != null && csvReport != null) {
            // Show notification
            val notification = Notification(
                "I18n Toolkit",
                "i18n Report Generated",
                "Reports saved to i18n-reports/ directory. Open HTML report?",
                NotificationType.INFORMATION
            )
            
            notification.addAction(object : com.intellij.notification.NotificationAction("Open HTML Report") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    FileEditorManager.getInstance(project).openFile(
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(htmlReport) ?: return,
                        true
                    )
                    notification.expire()
                }
            })
            
            Notifications.Bus.notify(notification, project)
        } else {
            Notifications.Bus.notify(
                Notification(
                    "I18n Toolkit",
                    "i18n Report Failed",
                    "No translations found to report. Ensure translation files are properly configured.",
                    NotificationType.WARNING
                ),
                project
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
