package com.github.yelog.i18nhelper.startup

import com.github.yelog.i18nhelper.service.I18nCacheService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class I18nProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().info("I18n Helper: Initializing for project ${project.name}")
        I18nCacheService.getInstance(project).initialize()
    }
}
