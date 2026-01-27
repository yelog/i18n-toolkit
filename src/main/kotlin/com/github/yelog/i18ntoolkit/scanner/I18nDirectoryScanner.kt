package com.github.yelog.i18ntoolkit.scanner

import com.github.yelog.i18ntoolkit.model.I18nDirectories
import com.github.yelog.i18ntoolkit.model.TranslationFileType
import com.github.yelog.i18ntoolkit.util.I18nLocaleUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil

object I18nDirectoryScanner {

    fun scanForTranslationFiles(project: Project): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        val translationFiles = mutableListOf<VirtualFile>()

        findI18nDirectories(baseDir).forEach { dir ->
            collectTranslationFiles(dir, translationFiles)
        }

        return translationFiles
    }

    fun findI18nDirectories(root: VirtualFile): List<VirtualFile> {
        val directories = mutableListOf<VirtualFile>()
        
        VfsUtil.iterateChildrenRecursively(root, { file ->
            !file.name.startsWith(".") && 
            file.name != "node_modules" && 
            file.name != "dist" && 
            file.name != "build" &&
            file.name != ".git"
        }) { file ->
            if (file.isDirectory && I18nDirectories.STANDARD_DIRS.contains(file.name.lowercase())) {
                directories.add(file)
            }
            true
        }

        return directories
    }

    private fun collectTranslationFiles(directory: VirtualFile, result: MutableList<VirtualFile>) {
        VfsUtil.iterateChildrenRecursively(directory, null) { file ->
            if (!file.isDirectory) {
                val ext = file.extension?.lowercase()
                if (ext != null && TranslationFileType.allExtensions().contains(ext)) {
                    result.add(file)
                }
            }
            true
        }
    }

    fun isTranslationFile(file: VirtualFile): Boolean {
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
