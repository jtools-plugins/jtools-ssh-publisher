package com.lhstack.ssh.util

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteRiskOperationUtilsTest {

    @Test
    fun delete_confirmation_for_directory_mentions_recursive_and_irreversible() {
        val confirmation = RemoteRiskOperationUtils.buildDeleteConfirmation(
            listOf(RemotePathItem(path = "/srv/app", isDirectory = true))
        )

        assertEquals("确认删除", confirmation.title)
        assertTrue(confirmation.message.contains("递归删除目录"))
        assertTrue(confirmation.message.contains("此操作不可恢复"))
    }

    @Test
    fun delete_confirmation_for_batch_reports_directory_count() {
        val confirmation = RemoteRiskOperationUtils.buildDeleteConfirmation(
            listOf(
                RemotePathItem(path = "/srv/app", isDirectory = true),
                RemotePathItem(path = "/srv/app/app.jar", isDirectory = false),
                RemotePathItem(path = "/srv/logs", isDirectory = true)
            )
        )

        assertEquals("确认批量删除", confirmation.title)
        assertTrue(confirmation.message.contains("选中的 3 项"))
        assertTrue(confirmation.message.contains("包含 2 个目录"))
        assertTrue(confirmation.message.contains("此操作不可恢复"))
    }

    @Test
    fun move_confirmation_lists_target_and_preview() {
        val confirmation = RemoteRiskOperationUtils.buildMoveConfirmation(
            items = listOf(
                RemotePathItem(path = "/srv/app", isDirectory = true),
                RemotePathItem(path = "/srv/app.yml", isDirectory = false)
            ),
            targetDirectory = "/opt/releases"
        )

        assertEquals("确认移动", confirmation.title)
        assertTrue(confirmation.message.contains("/opt/releases"))
        assertTrue(confirmation.message.contains("将把 2 项移动到"))
        assertTrue(confirmation.message.contains("目录会整体迁移"))
        assertTrue(confirmation.message.contains("/srv/app"))
    }

    @Test
    fun upload_conflict_analysis_reports_existing_paths() {
        val tempDir = createTempDirectory().toFile()
        val localDir = File(tempDir, "deploy").apply { mkdirs() }
        File(localDir, "app.jar").writeText("jar")
        File(localDir, "logs").mkdirs()

        val plan = SftpTreeOperationUtils.buildUploadPlan(listOf(localDir), "/remote")
        val analysis = RemoteRiskOperationUtils.analyzeUploadConflicts(
            plan = plan,
            existingEntries = mapOf(
                "/remote/deploy" to true,
                "/remote/deploy/app.jar" to false
            )
        )

        assertEquals(2, analysis.directoryCount)
        assertEquals(1, analysis.fileCount)
        assertEquals(2, analysis.conflicts.size)
        assertTrue(analysis.conflicts.any { it.remotePath == "/remote/deploy" && it.existingDirectory })
        assertTrue(analysis.conflicts.any { it.remotePath == "/remote/deploy/app.jar" && !it.existingDirectory })
    }

    @Test
    fun upload_confirmation_mentions_conflicts_and_mode_options() {
        val confirmation = RemoteRiskOperationUtils.buildUploadConfirmation(
            targetDirectory = "/remote",
            analysis = UploadConflictAnalysis(
                fileCount = 3,
                directoryCount = 1,
                conflicts = listOf(
                    UploadConflictItem(remotePath = "/remote/deploy", isDirectory = true, existingDirectory = true),
                    UploadConflictItem(remotePath = "/remote/deploy/app.jar", isDirectory = false, existingDirectory = false)
                )
            )
        )

        assertEquals("确认上传", confirmation.title)
        assertTrue(confirmation.message.contains("将上传 3 个文件，1 个目录"))
        assertTrue(confirmation.message.contains("检测到 2 项同名目标"))
        assertTrue(confirmation.message.contains("覆盖上传"))
        assertTrue(confirmation.message.contains("跳过冲突项"))
    }
}
