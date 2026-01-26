package com.github.yelog.i18nhelper.util

import com.github.yelog.i18nhelper.settings.I18nSettingsState
import com.intellij.openapi.project.Project

/**
 * Utility class to resolve i18n function names from settings.
 * Provides a unified way to get the list of i18n functions across the plugin.
 */
object I18nFunctionResolver {

    /**
     * Default i18n functions if not configured
     */
    private val DEFAULT_FUNCTIONS = setOf("t", "\$t", "i18n", "i18next", "translate", "formatMessage")

    /**
     * Get the list of i18n function names from project settings.
     * Falls back to default functions if settings not available.
     */
    fun getFunctions(project: Project): Set<String> {
        return try {
            val settings = I18nSettingsState.getInstance(project)
            val customFunctions = settings.getI18nFunctions()
            if (customFunctions.isEmpty()) {
                DEFAULT_FUNCTIONS
            } else {
                customFunctions
            }
        } catch (e: Exception) {
            DEFAULT_FUNCTIONS
        }
    }

    /**
     * Check if a given function name is an i18n function
     */
    fun isI18nFunction(project: Project, functionName: String): Boolean {
        return getFunctions(project).contains(functionName)
    }
}
