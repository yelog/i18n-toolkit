package com.github.yelog.i18ntoolkit.scanner

import com.github.yelog.i18ntoolkit.model.I18nDirectories
import com.github.yelog.i18ntoolkit.model.TranslationFileType
import com.github.yelog.i18ntoolkit.util.I18nLocaleUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil

object I18nDirectoryScanner {

    private val excludedDirNames = setOf("node_modules", "dist", "build", "target", "out")

    /**
     * Regex pattern for Spring locale message bundle files:
     * messages_en_US.properties, messages_zh_CN.properties, messages-en.properties, etc.
     */
    private val SPRING_LOCALE_MESSAGE_PATTERN =
        Regex("^messages[_-][a-zA-Z]{2}(?:[_-][a-zA-Z]{2})?\\.properties$", RegexOption.IGNORE_CASE)

    fun scanForTranslationFiles(project: Project): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        val translationFiles = mutableListOf<VirtualFile>()

        findI18nDirectories(baseDir).forEach { dir ->
            collectTranslationFiles(dir, translationFiles)
        }

        // Also scan for Spring locale message properties in src/main/resources directories
        findSpringMessageFiles(baseDir, translationFiles)

        return translationFiles
    }

    fun findI18nDirectories(root: VirtualFile): List<VirtualFile> {
        val directories = mutableListOf<VirtualFile>()
        
        VfsUtil.iterateChildrenRecursively(root, ::shouldTraverse) { file ->
            if (file.isDirectory && I18nDirectories.STANDARD_DIRS.contains(file.name.lowercase())) {
                directories.add(file)
            }
            true
        }

        return directories
    }

    private fun collectTranslationFiles(directory: VirtualFile, result: MutableList<VirtualFile>) {
        VfsUtil.iterateChildrenRecursively(directory, ::shouldTraverse) { file ->
            if (!file.isDirectory) {
                val ext = file.extension?.lowercase()
                if (ext != null && TranslationFileType.allExtensions().contains(ext)) {
                    result.add(file)
                }
            }
            true
        }
    }

    private fun shouldTraverse(file: VirtualFile): Boolean {
        if (!file.isDirectory) return true
        val name = file.name
        if (name.startsWith(".")) return false
        return name.lowercase() !in excludedDirNames
    }

    /**
     * Find Spring locale message bundle files (messages_<locale>.properties) in src/main/resources.
     */
    private fun findSpringMessageFiles(root: VirtualFile, result: MutableList<VirtualFile>) {
        VfsUtil.iterateChildrenRecursively(root, ::shouldTraverse) { file ->
            if (!file.isDirectory && isSpringLocaleMessageFile(file)) {
                // Check that it's under a "resources" directory (typically src/main/resources)
                if (isUnderResourcesDir(file)) {
                    if (!result.contains(file)) {
                        result.add(file)
                    }
                }
            }
            true
        }
    }

    fun isSpringLocaleMessageFile(file: VirtualFile): Boolean = SPRING_LOCALE_MESSAGE_PATTERN.matches(file.name)

    /**
     * Check if a file is under a "resources" directory.
     */
    private fun isUnderResourcesDir(file: VirtualFile): Boolean {
        var parent = file.parent
        while (parent != null) {
            if (parent.name == "resources") return true
            parent = parent.parent
        }
        return false
    }

    fun isTranslationFile(file: VirtualFile): Boolean {
        // Check for Spring message files
        if (isSpringLocaleMessageFile(file) && isUnderResourcesDir(file)) return true

        val ext = file.extension?.lowercase() ?: return false
        if (!TranslationFileType.allExtensions().contains(ext)) return false

        var parent = file.parent
        while (parent != null) {
            if (I18nDirectories.STANDARD_DIRS.contains(parent.name.lowercase())) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    fun getLocaleFromPath(file: VirtualFile): String {
        val pathParts = file.path.split("/")
        val fileName = file.nameWithoutExtension

        for (i in pathParts.indices.reversed()) {
            val part = pathParts[i]
            if (isLocaleName(part)) {
                return part
            }
        }

        if (isLocaleName(fileName)) {
            return fileName
        }

        return "unknown"
    }

    private fun isLocaleName(name: String): Boolean = I18nLocaleUtils.isLocaleName(name)
}
