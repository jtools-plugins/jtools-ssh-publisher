package com.lhstack.ssh.util

import java.io.File
import kotlin.io.path.createTempDirectory

private fun assertState(condition: Boolean, message: String) {
    if (!condition) {
        throw IllegalStateException(message)
    }
}

fun main() {
    val normalized = SftpTreeOperationUtils.deduplicatePaths(
        listOf("/data", "/data/logs", "/data/logs/app.log", "/opt/app")
    )
    assertState(normalized == listOf("/data", "/opt/app"), "descendant paths should be removed when parent is selected")

    assertState(
        SftpTreeOperationUtils.isMoveIntoSelf(sourcePath = "/data/logs", targetDirectory = "/data/logs/archive"),
        "moving a directory into its child should be rejected"
    )
    assertState(
        !SftpTreeOperationUtils.isMoveIntoSelf(sourcePath = "/data/logs", targetDirectory = "/data/archive"),
        "moving a directory outside itself should be allowed"
    )

    val root = createTempDirectory("sftp-tree-utils").toFile()
    val nestedDir = File(root, "assets/images").apply { mkdirs() }
    File(root, "assets/app.yml").writeText("x")
    File(nestedDir, "logo.svg").writeText("svg")
    File(root, "README.md").writeText("doc")
    File(root, "empty-dir").mkdirs()

    val plan = SftpTreeOperationUtils.buildUploadPlan(
        sources = listOf(File(root, "assets"), File(root, "README.md"), File(root, "empty-dir")),
        targetDirectory = "/remote/work"
    )

    assertState("/remote/work/assets" in plan.directories, "top-level directory should be created remotely")
    assertState("/remote/work/assets/images" in plan.directories, "nested directory should be created remotely")
    assertState("/remote/work/empty-dir" in plan.directories, "empty directory should still be created remotely")
    assertState(
        plan.files.any { it.localFile.name == "app.yml" && it.remotePath == "/remote/work/assets/app.yml" },
        "directory upload should preserve relative structure"
    )
    assertState(
        plan.files.any { it.localFile.name == "README.md" && it.remotePath == "/remote/work/README.md" },
        "plain file upload should land in the target directory root"
    )

    assertState(
        SftpTreeOperationUtils.resolveRefreshDirectory(
            targetDirectory = "/remote/work",
            currentPath = "/remote/work",
            visibleDirectories = emptySet<String>()
        ) == "/remote/work",
        "uploading into the current directory should refresh the current root"
    )
    assertState(
        SftpTreeOperationUtils.resolveRefreshDirectory(
            targetDirectory = "/remote/work/assets",
            currentPath = "/remote/work",
            visibleDirectories = setOf("/remote/work/assets")
        ) == "/remote/work/assets",
        "visible target directory should be refreshed directly"
    )
    assertState(
        SftpTreeOperationUtils.resolveRefreshDirectory(
            targetDirectory = "/remote/work/assets/images",
            currentPath = "/remote/work",
            visibleDirectories = setOf("/remote/work/assets")
        ) == "/remote/work/assets",
        "new nested directory should fall back to the nearest visible ancestor"
    )

    root.deleteRecursively()
}
