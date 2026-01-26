package com.github.yelog.i18nhelper.detector

import com.github.yelog.i18nhelper.model.I18nFramework
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

object I18nFrameworkDetector {

    private val I18N_PACKAGES = listOf(
        "vue-i18n",
        "react-i18next",
        "i18next",
        "next-intl",
        "@nuxtjs/i18n",
        "react-intl"
    )

    fun detect(project: Project): I18nFramework {
        val baseDir = project.basePath ?: return I18nFramework.UNKNOWN

        // Check for Spring Boot project first (Java/Kotlin projects)
        if (isSpringBootProject(project, baseDir)) {
            return I18nFramework.SPRING_MESSAGE
        }

        // Then check for JavaScript/TypeScript projects
        val packageJsonFile = findPackageJson(project, baseDir) ?: return I18nFramework.UNKNOWN
        return parsePackageJson(project, packageJsonFile)
    }

    private fun isSpringBootProject(project: Project, basePath: String): Boolean {
        val baseDir = project.guessProjectDir() ?: return false

        // Check for pom.xml (Maven)
        val pomFile = baseDir.findChild("pom.xml")
        if (pomFile != null && containsSpringDependency(pomFile, project)) {
            return true
        }

        // Check for build.gradle or build.gradle.kts (Gradle)
        val gradleFile = baseDir.findChild("build.gradle") ?: baseDir.findChild("build.gradle.kts")
        if (gradleFile != null && containsSpringDependency(gradleFile, project)) {
            return true
        }

        return false
    }

    private fun containsSpringDependency(file: VirtualFile, project: Project): Boolean {
        try {
            val content = String(file.contentsToByteArray())
            val springPatterns = listOf(
                "spring-boot-starter",
                "spring-context",
                "org.springframework.boot",
                "org.springframework:spring-context"
            )
            return springPatterns.any { content.contains(it) }
        } catch (e: Exception) {
            return false
        }
    }

    private fun findPackageJson(project: Project, basePath: String): VirtualFile? {
        val baseDir = project.guessProjectDir() ?: return null
        return baseDir.findChild("package.json")
    }

    private fun parsePackageJson(project: Project, file: VirtualFile): I18nFramework {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return I18nFramework.UNKNOWN
        val rootObject = psiFile.topLevelValue as? JsonObject ?: return I18nFramework.UNKNOWN

        val dependencies = mutableSetOf<String>()
        
        listOf("dependencies", "devDependencies", "peerDependencies").forEach { depType ->
            rootObject.findProperty(depType)?.value?.let { value ->
                if (value is JsonObject) {
                    value.propertyList.forEach { prop ->
                        dependencies.add(prop.name)
                    }
                }
            }
        }

        for (packageName in I18N_PACKAGES) {
            if (dependencies.contains(packageName)) {
                return I18nFramework.fromPackageName(packageName)
            }
        }

        return I18nFramework.UNKNOWN
    }

    fun detectAll(project: Project): Set<I18nFramework> {
        val baseDir = project.basePath ?: return emptySet()
        val packageJsonFile = findPackageJson(project, baseDir) ?: return emptySet()
        return parseAllFrameworks(project, packageJsonFile)
    }

    private fun parseAllFrameworks(project: Project, file: VirtualFile): Set<I18nFramework> {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return emptySet()
        val rootObject = psiFile.topLevelValue as? JsonObject ?: return emptySet()

        val dependencies = mutableSetOf<String>()
        
        listOf("dependencies", "devDependencies", "peerDependencies").forEach { depType ->
            rootObject.findProperty(depType)?.value?.let { value ->
                if (value is JsonObject) {
                    value.propertyList.forEach { prop ->
                        dependencies.add(prop.name)
                    }
                }
            }
        }

        return I18N_PACKAGES
            .filter { dependencies.contains(it) }
            .map { I18nFramework.fromPackageName(it) }
            .toSet()
    }
}
