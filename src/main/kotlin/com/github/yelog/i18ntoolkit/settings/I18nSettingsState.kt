package com.github.yelog.i18ntoolkit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

enum class I18nDisplayMode {
    INLINE,
    TRANSLATION_ONLY
}

enum class I18nFrameworkSetting(val displayName: String) {
    AUTO("auto"),
    VUE_I18N("vue-i18n"),
    REACT_I18NEXT("react-i18next"),
    I18NEXT("i18next"),
    NEXT_INTL("next-intl"),
    NUXT_I18N("@nuxtjs/i18n"),
    REACT_INTL("react-intl"),
    SPRING_MESSAGE("spring message")
}

@Service(Service.Level.PROJECT)
@State(
    name = "I18nToolkitSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class I18nSettingsState(private val project: Project) : PersistentStateComponent<I18nSettingsState.State> {

    data class State(
        var displayLocale: String = "",
        var displayMode: I18nDisplayMode = I18nDisplayMode.INLINE,
        var frameworkSetting: I18nFrameworkSetting = I18nFrameworkSetting.AUTO,
        var customI18nFunctions: String = "t, \$t"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getDisplayLocaleOrNull(): String? {
        return state.displayLocale.takeIf { it.isNotEmpty() }
    }

    fun getI18nFunctions(): Set<String> {
        return parseI18nFunctions(state.customI18nFunctions)
    }

    private fun parseI18nFunctions(input: String): Set<String> {
        return input.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    companion object {
        fun getInstance(project: Project): I18nSettingsState {
            return project.getService(I18nSettingsState::class.java)
        }
    }
}
