package com.github.yelog.i18ntoolkit.util

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Resolves IntelliJ modules for files, mapping to Gradle/Maven sub-modules
 * in Spring Cloud microservice projects.
 */
object I18nModuleResolver {

    /**
     * Get the module name for a given file.
     * Returns null if the file doesn't belong to any module.
     */
    fun getModuleName(project: Project, file: VirtualFile): String? {
        val module = ModuleUtilCore.findModuleForFile(file, project) ?: return null
        return module.name
    }

    /**
     * Get module names that the given module depends on (for shared translations).
     */
    fun getDependencyModuleNames(project: Project, moduleName: String): List<String> {
        val moduleManager = ModuleManager.getInstance(project)
        val module = moduleManager.findModuleByName(moduleName) ?: return emptyList()
        return ModuleRootManager.getInstance(module).dependencies.map { it.name }
    }

    /**
     * Check if the project has multiple modules (likely a multi-module project).
     */
    fun isMultiModuleProject(project: Project): Boolean {
        return ModuleManager.getInstance(project).modules.size > 1
    }
}
