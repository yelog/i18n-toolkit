package com.github.yelog.i18ntoolkit.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

object I18nDefaultNamespaceResolver {

    private val sourceExtensions = setOf("js", "mjs", "cjs", "ts", "mts", "cts", "json")
    private val excludedDirectoryNames = setOf("node_modules", "dist", "build", "target", "out")
    private val configBaseNames = setOf("config", "i18n", "i18next", "next-i18next")
    private val defaultNamespacePattern = Regex("""[\"']?defaultNS[\"']?\s*:\s*[\"']([^\"']+)[\"']""")

    fun findDefaultNamespace(project: Project): String? {
        val root = project.guessProjectDir() ?: return null
        var defaultNamespace: String? = null

        VfsUtil.iterateChildrenRecursively(root, ::shouldTraverse) { file ->
            if (isPotentialConfigFile(file)) {
                val match = defaultNamespacePattern.find(readText(file))
                if (match != null) {
                    defaultNamespace = match.groupValues[1]
                    return@iterateChildrenRecursively false
                }
            }
            true
        }

        return defaultNamespace
    }

    fun isPotentialConfigFile(file: VirtualFile): Boolean {
        if (file.isDirectory || file.extension?.lowercase() !in sourceExtensions) return false

        return file.nameWithoutExtension.lowercase() in configBaseNames
    }

    private fun shouldTraverse(file: VirtualFile): Boolean {
        return !file.isDirectory || !file.name.startsWith(".") && file.name.lowercase() !in excludedDirectoryNames
    }

    private fun readText(file: VirtualFile): String {
        return try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
