package com.github.yelog.i18nhelper.startup

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.github.yelog.i18nhelper.util.I18nUiRefresher
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity

class I18nProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().info("I18n Helper: Initializing for project ${project.name}")
        I18nCacheService.getInstance(project).initialize()
    }
}

/**
 * Listener for dynamic plugin loading - initializes cache when plugin is loaded
 * without requiring IDE restart
 */
class I18nDynamicPluginListener : DynamicPluginListener {

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == "com.github.yelog.i18nhelper") {
            thisLogger().info("I18n Helper: Plugin dynamically loaded, initializing...")
            // Initialize cache for all open projects
            ProjectManager.getInstance().openProjects.forEach { project ->
                if (!project.isDisposed) {
                    I18nCacheService.getInstance(project).initialize()
                    I18nUiRefresher.refresh(project)
                }
            }
        }
    }
}
