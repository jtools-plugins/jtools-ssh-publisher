package com.lhstack.ssh.model

import java.io.File
import java.util.UUID

/**
 * 传输任务（上传/下载）
 */
data class TransferTask(
    val id: String = UUID.randomUUID().toString(),
    val type: TransferType,
    val localFile: File,
    val remotePath: String,
    val config: SshConfig,
    val fileSize: Long = 0,  // 文件大小（下载时需要）
    val preScripts: List<ScriptConfig> = emptyList(),
    val postScripts: List<ScriptConfig> = emptyList(),
    val tempPreScript: String = "",
    val tempPostScript: String = "",
    /** 临时本地前置脚本（上传前在本机执行） */
    val tempLocalPreScript: String = "",
    /** 临时本地前置脚本使用的 Shell 类型 */
    val tempLocalPreShellType: ScriptConfig.ShellType = ScriptConfig.ShellType.DEFAULT,
    /** 临时本地后置脚本（上传后在本机执行） */
    val tempLocalPostScript: String = "",
    /** 临时本地后置脚本使用的 Shell 类型 */
    val tempLocalPostShellType: ScriptConfig.ShellType = ScriptConfig.ShellType.DEFAULT,
    var status: TaskStatus = TaskStatus.PENDING,
    var progress: Int = 0,
    var message: String = "等待中",
    val logs: MutableList<String> = mutableListOf(),
    val createTime: Long = System.currentTimeMillis()
) {
    enum class TransferType {
        UPLOAD,     // 上传
        DOWNLOAD    // 下载
    }

    enum class TaskStatus {
        PENDING,    // 等待中
        RUNNING,    // 执行中
        SUCCESS,    // 成功
        FAILED,     // 失败
        STOPPED     // 已停止
    }

    fun addLog(log: String) {
        logs.add("[${java.text.SimpleDateFormat("HH:mm:ss").format(System.currentTimeMillis())}] $log")
    }

    val displayName: String
        get() = when (type) {
            TransferType.UPLOAD -> "↑ ${localFile.name}"
            TransferType.DOWNLOAD -> "↓ ${localFile.name}"
        }
}
