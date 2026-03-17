package com.lhstack.ssh.util

import java.io.File

data class UploadPlanItem(
    val localFile: File,
    val remotePath: String
)

data class UploadPlan(
    val directories: Set<String>,
    val files: List<UploadPlanItem>
)

object SftpTreeOperationUtils {

    fun deduplicatePaths(paths: List<String>): List<String> {
        return paths.distinct()
            .sorted()
            .filter { candidate ->
                paths.none { other ->
                    other != candidate && isDescendant(path = candidate, ancestor = other)
                }
            }
    }

    fun isMoveIntoSelf(sourcePath: String, targetDirectory: String): Boolean {
        return targetDirectory == sourcePath || isDescendant(path = targetDirectory, ancestor = sourcePath)
    }

    fun buildUploadPlan(sources: List<File>, targetDirectory: String): UploadPlan {
        val directories = linkedSetOf<String>()
        val files = mutableListOf<UploadPlanItem>()
        sources.filter { it.exists() }.forEach { source ->
            if (source.isDirectory) {
                collectDirectoryPlan(
                    root = source,
                    current = source,
                    remoteBase = UploadPathUtils.buildRemotePath(targetDirectory, source.name),
                    directories = directories,
                    files = files
                )
            } else if (source.isFile) {
                files.add(
                    UploadPlanItem(
                        localFile = source,
                        remotePath = UploadPathUtils.buildRemotePath(targetDirectory, source.name)
                    )
                )
            }
        }
        return UploadPlan(directories = directories, files = files)
    }

    fun resolveRefreshDirectory(
        targetDirectory: String,
        currentPath: String,
        visibleDirectories: Set<String>
    ): String {
        if (targetDirectory == currentPath) {
            return currentPath
        }
        var candidate = targetDirectory
        while (true) {
            if (candidate in visibleDirectories) {
                return candidate
            }
            if (candidate == currentPath || candidate == "/") {
                return currentPath
            }
            candidate = parentDirectory(candidate)
        }
    }

    fun parentDirectory(path: String): String {
        if (path.isBlank() || path == "/") {
            return "/"
        }
        return path.substringBeforeLast("/", "").ifEmpty { "/" }
    }

    private fun collectDirectoryPlan(
        root: File,
        current: File,
        remoteBase: String,
        directories: MutableSet<String>,
        files: MutableList<UploadPlanItem>
    ) {
        val relativePath = current.relativeTo(root).invariantSeparatorsPath
        val currentRemoteDir = if (relativePath.isEmpty()) remoteBase else UploadPathUtils.buildRemotePath(remoteBase, relativePath)
        directories.add(currentRemoteDir)

        current.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) {
                collectDirectoryPlan(root, child, remoteBase, directories, files)
            } else if (child.isFile) {
                val childRelative = child.relativeTo(root).invariantSeparatorsPath
                files.add(
                    UploadPlanItem(
                        localFile = child,
                        remotePath = UploadPathUtils.buildRemotePath(remoteBase, childRelative)
                    )
                )
            }
        }
    }

    private fun isDescendant(path: String, ancestor: String): Boolean {
        if (path == ancestor) {
            return false
        }
        val normalizedAncestor = if (ancestor == "/") "/" else ancestor.trimEnd('/')
        return if (normalizedAncestor == "/") {
            path.startsWith("/")
        } else {
            path.startsWith("$normalizedAncestor/")
        }
    }
}
