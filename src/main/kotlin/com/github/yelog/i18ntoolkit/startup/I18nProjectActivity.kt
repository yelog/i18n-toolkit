package com.github.yelog.i18ntoolkit.startup

import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity

class I18nProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().info("I18n Toolkit: Initializing for project ${project.name}")
        I18nCacheService.getInstance(project).initialize()
    }
}

/**
 * Listener for dynamic plugin loading - initializes cache when plugin is loaded
 * without requiring IDE restart
 */
class I18nDynamicPluginListener : DynamicPluginListener {

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == "com.github.yelog.i18ntoolkit") {
            thisLogger().info("I18n Toolkit: Plugin dynamically loaded, initializing...")
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
