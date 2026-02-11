package com.github.yelog.i18ntoolkit.startup

import com.github.yelog.i18ntoolkit.action.I18nQuickDocAction
import com.github.yelog.i18ntoolkit.service.I18nCacheService
import com.github.yelog.i18ntoolkit.util.I18nUiRefresher
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity

class I18nProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().info("I18n Toolkit: Initializing for project ${project.name}")
        I18nCacheService.getInstance(project).initialize()
        installQuickDocOverride()
    }

    companion object {
        private var installed = false

        @Synchronized
        fun installQuickDocOverride() {
            if (installed) return
            val actionManager = ActionManager.getInstance()
            val original = actionManager.getAction("QuickJavaDoc") ?: return
            if (original is I18nQuickDocAction) return
            actionManager.replaceAction("QuickJavaDoc", I18nQuickDocAction(original))
            installed = true
        }

        @Synchronized
        fun uninstallQuickDocOverride() {
            if (!installed) return
            val actionManager = ActionManager.getInstance()
            val current = actionManager.getAction("QuickJavaDoc") as? I18nQuickDocAction ?: return
            // We can't easily restore the original since replaceAction doesn't give it back,
            // but the wrapped original is kept by our action. Just unregister+re-register.
            actionManager.replaceAction("QuickJavaDoc", current.originalAction)
            installed = false
        }
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

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString == "com.github.yelog.i18ntoolkit") {
            I18nProjectActivity.uninstallQuickDocOverride()
        }
    }

    override fun beforePluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        // No-op: we don't need to do anything before plugin loads
    }

    override fun pluginUnloaded(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        // No-op: cleanup is handled automatically
    }

    override fun beforePluginsLoaded() {
        // No-op: we don't need to do anything before plugins load
    }

    override fun pluginsLoaded() {
        // No-op: initialization is handled in pluginLoaded()
    }
}
