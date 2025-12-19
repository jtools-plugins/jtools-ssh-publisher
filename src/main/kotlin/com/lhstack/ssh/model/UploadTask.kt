package com.lhstack.ssh.model

import java.io.File
import java.util.UUID

/**
 * 上传任务状态
 */
enum class UploadStatus {
    PENDING,    // 等待中
    UPLOADING,  // 上传中
    SUCCESS,    // 成功
    FAILED,     // 失败
    CANCELLED   // 已取消
}

/**
 * 上传任务
 */
data class UploadTask(
    val id: String = UUID.randomUUID().toString(),
    val localFile: File,
    val remotePath: String,
    val remoteFileName: String,
    val config: SshConfig,
    var status: UploadStatus = UploadStatus.PENDING,
    var progress: Int = 0,
    var uploadedBytes: Long = 0,
    var totalBytes: Long = localFile.length(),
    var errorMessage: String? = null,
    var preScripts: List<ScriptConfig> = emptyList(),
    var postScripts: List<ScriptConfig> = emptyList(),
    var tempPreScript: String = "",
    var tempPostScript: String = ""
) {
    val fullRemotePath: String
        get() = if (remotePath.endsWith("/")) {
            remotePath + remoteFileName
        } else {
            "$remotePath/$remoteFileName"
        }
    
    val displayName: String
        get() = "${config.name}: ${localFile.name} -> $remoteFileName"
}
