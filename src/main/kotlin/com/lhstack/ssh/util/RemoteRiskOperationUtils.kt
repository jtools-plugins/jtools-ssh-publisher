package com.lhstack.ssh.util

data class RemotePathItem(
    val path: String,
    val isDirectory: Boolean
) {
    val displayPath: String
        get() = if (isDirectory) "$path/" else path
}

data class ConfirmationContent(
    val title: String,
    val message: String
)

data class UploadConflictItem(
    val remotePath: String,
    val isDirectory: Boolean,
    val existingDirectory: Boolean
)

data class UploadConflictAnalysis(
    val fileCount: Int,
    val directoryCount: Int,
    val conflicts: List<UploadConflictItem>
)

object RemoteRiskOperationUtils {

    fun buildDeleteConfirmation(items: List<RemotePathItem>): ConfirmationContent {
        require(items.isNotEmpty()) { "items must not be empty" }
        if (items.size == 1) {
            val item = items.first()
            val message = if (item.isDirectory) {
                "确定递归删除目录 \"${item.path.substringAfterLast('/').ifEmpty { item.path }}\" 及其全部内容吗？此操作不可恢复。"
            } else {
                "确定删除文件 \"${item.path.substringAfterLast('/')}\" 吗？此操作不可恢复。"
            }
            return ConfirmationContent(title = "确认删除", message = message)
        }

        val directoryCount = items.count { it.isDirectory }
        val preview = items.take(5).joinToString("\n") { "- ${it.displayPath}" }
        val suffix = if (items.size > 5) "\n... 还有 ${items.size - 5} 项" else ""
        return ConfirmationContent(
            title = "确认批量删除",
            message = buildString {
                append("确定递归删除选中的 ${items.size} 项吗？")
                if (directoryCount > 0) {
                    append("其中包含 ${directoryCount} 个目录。")
                }
                append("此操作不可恢复。")
                appendLine()
                appendLine()
                append(preview)
                append(suffix)
            }.trim()
        )
    }

    fun buildMoveConfirmation(items: List<RemotePathItem>, targetDirectory: String): ConfirmationContent {
        require(items.isNotEmpty()) { "items must not be empty" }
        val containsDirectory = items.any { it.isDirectory }
        val preview = items.take(5).joinToString("\n") { "- ${it.displayPath}" }
        val suffix = if (items.size > 5) "\n... 还有 ${items.size - 5} 项" else ""
        return ConfirmationContent(
            title = "确认移动",
            message = buildString {
                if (items.size == 1) {
                    val item = items.first()
                    if (item.isDirectory) {
                        append("将把目录 \"${item.path.substringAfterLast('/').ifEmpty { item.path }}\" 及其全部内容移动到 \"$targetDirectory\"，是否继续？")
                    } else {
                        append("将把文件 \"${item.path.substringAfterLast('/')}\" 移动到 \"$targetDirectory\"，是否继续？")
                    }
                } else {
                    append("将把 ${items.size} 项移动到 \"$targetDirectory\"，是否继续？")
                }
                if (containsDirectory) {
                    appendLine()
                    appendLine()
                    append("目录会整体迁移，原位置内容将消失。")
                }
                appendLine()
                appendLine()
                append(preview)
                append(suffix)
            }.trim()
        )
    }

    fun analyzeUploadConflicts(
        plan: UploadPlan,
        existingEntries: Map<String, Boolean>
    ): UploadConflictAnalysis {
        val conflicts = mutableListOf<UploadConflictItem>()
        plan.directories.forEach { remotePath ->
            existingEntries[remotePath]?.let { existingIsDirectory ->
                conflicts += UploadConflictItem(
                    remotePath = remotePath,
                    isDirectory = true,
                    existingDirectory = existingIsDirectory
                )
            }
        }
        plan.files.forEach { item ->
            existingEntries[item.remotePath]?.let { existingIsDirectory ->
                conflicts += UploadConflictItem(
                    remotePath = item.remotePath,
                    isDirectory = false,
                    existingDirectory = existingIsDirectory
                )
            }
        }
        return UploadConflictAnalysis(
            fileCount = plan.files.size,
            directoryCount = plan.directories.size,
            conflicts = conflicts.sortedBy { it.remotePath }
        )
    }

    fun buildUploadConfirmation(targetDirectory: String, analysis: UploadConflictAnalysis): ConfirmationContent {
        val preview = analysis.conflicts.take(5).joinToString("\n") {
            val type = if (it.isDirectory) "[目录]" else "[文件]"
            "$type ${it.remotePath}"
        }
        val suffix = if (analysis.conflicts.size > 5) "\n... 还有 ${analysis.conflicts.size - 5} 项" else ""
        return ConfirmationContent(
            title = "确认上传",
            message = buildString {
                append("目标目录: $targetDirectory")
                appendLine()
                append("将上传 ${analysis.fileCount} 个文件，${analysis.directoryCount} 个目录。")
                if (analysis.conflicts.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    append("检测到 ${analysis.conflicts.size} 项同名目标，可能发生覆盖或目录合并。")
                    appendLine()
                    append(preview)
                    append(suffix)
                    appendLine()
                    appendLine()
                    append("请选择“覆盖上传”或“跳过冲突项”。")
                }
            }.trim()
        )
    }
}
