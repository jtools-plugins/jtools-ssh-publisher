package com.lhstack.ssh.service

import com.intellij.AppTopics
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.MessageBusConnection
import com.lhstack.ssh.model.SshConfig
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 远程文件编辑服务
 * 下载远程文件到本地，用 IDEA 打开编辑，保存时同步回服务器
 */
class RemoteFileEditorService(
    private val project: Project,
    private val connectionManager: SshConnectionManager,
    private val config: SshConfig
) : Disposable {

    private val executor = Executors.newSingleThreadExecutor()
    private val openedFiles = ConcurrentHashMap<String, RemoteFileInfo>()  // localPath -> RemoteFileInfo
    private var messageBusConnection: MessageBusConnection? = null

    // 本地缓存目录
    private val cacheDir: File by lazy {
        val baseDir = File(System.getProperty("user.home"), ".jtools/jtools-ssh-publisher/${config.id}")
        baseDir.mkdirs()
        baseDir
    }

    data class RemoteFileInfo(
        val remotePath: String,
        val localFile: File,
        val virtualFilePath: String? = null,  // VirtualFile 的 path，用于匹配保存事件
        var lastSyncTime: Long = System.currentTimeMillis()
    )

    init {
        setupListeners()
    }

    private fun setupListeners() {
        messageBusConnection = ApplicationManager.getApplication().messageBus.connect(this)
        
        // 监听文件关闭事件，清理记录
        messageBusConnection?.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                // VirtualFile.path 可能与 File.absolutePath 格式不同，需要统一处理
                val normalizedPath = file.path.replace("/", File.separator)
                openedFiles.remove(normalizedPath)
                openedFiles.remove(file.path)
                println("[SFTP] 文件关闭: ${file.path}")
            }
        })
        
        // 监听文件保存事件，同步到远程
        messageBusConnection?.subscribe(AppTopics.FILE_DOCUMENT_SYNC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                println("[SFTP] 文件保存事件: ${file.path}")
                println("[SFTP] 当前已打开文件 keys: ${openedFiles.keys}")
                
                // 直接使用 VirtualFile.path 查找（这是最准确的匹配方式）
                val fileInfo = openedFiles[file.path]
                
                if (fileInfo != null) {
                    println("[SFTP] 找到匹配文件，开始同步: ${fileInfo.remotePath}")
                    syncToRemote(fileInfo, document.text)
                } else {
                    println("[SFTP] 未找到匹配的远程文件记录: ${file.path}")
                }
            }
        })
    }

    /**
     * 在编辑器中打开远程文件
     */
    fun openRemoteFile(remotePath: String, fileName: String, onError: (String) -> Unit) {
        // 检查连接状态
        if (!connectionManager.isConnected()) {
            onError("SSH 连接不可用")
            return
        }
        
        // 计算本地文件路径
        val localFile = getLocalFile(remotePath)
        
        // 检查是否已经打开
        val existingInfo = openedFiles[localFile.absolutePath]
        if (existingInfo != null) {
            // 已打开，直接聚焦
            ApplicationManager.getApplication().invokeLater {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
            return
        }

        // 后台下载文件
        executor.submit {
            try {
                // 确保目录存在
                localFile.parentFile.mkdirs()
                
                // 下载文件
                val sftp = connectionManager.getSftpClient()
                sftp.read(remotePath).use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // 在 IDEA 中打开，并记录 VirtualFile 的 path
                ApplicationManager.getApplication().invokeLater {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
                    if (vf != null) {
                        vf.refresh(false, false)
                        
                        // 记录文件信息，使用 VirtualFile.path 作为主 key（这是保存事件中使用的格式）
                        val fileInfo = RemoteFileInfo(remotePath, localFile, vf.path)
                        openedFiles[vf.path] = fileInfo
                        // 同时用本地文件路径作为备用 key
                        openedFiles[localFile.absolutePath] = fileInfo
                        println("[SFTP] 文件已打开，VirtualFile.path: ${vf.path}, localFile: ${localFile.absolutePath}")
                        
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    } else {
                        onError("无法打开本地文件")
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    onError("下载文件失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 获取远程文件对应的本地缓存路径
     */
    private fun getLocalFile(remotePath: String): File {
        // 移除开头的 /，保持目录结构
        val relativePath = remotePath.removePrefix("/")
        return File(cacheDir, relativePath)
    }

    /**
     * 同步文件到远程服务器
     */
    private fun syncToRemote(fileInfo: RemoteFileInfo, content: String) {
        executor.submit {
            try {
                // 检查连接状态，断开则尝试重连
                if (!connectionManager.isConnected()) {
                    println("[SFTP] 连接已断开，尝试重连...")
                    if (!connectionManager.connect(config)) {
                        showSyncError(fileInfo.remotePath, "SSH 连接已断开，重连失败")
                        return@submit
                    }
                    println("[SFTP] 重连成功")
                }
                
                val sftp = connectionManager.getSftpClient()
                sftp.write(fileInfo.remotePath).use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                }
                fileInfo.lastSyncTime = System.currentTimeMillis()
                println("[SFTP] 文件已同步: ${fileInfo.remotePath}")
                
                // 显示同步成功通知
                showSyncSuccess(fileInfo.remotePath)
                
            } catch (e: Exception) {
                // 如果是连接问题，尝试重连后再次同步
                if (e.message?.contains("closed") == true || e.message?.contains("disconnect") == true) {
                    println("[SFTP] 连接异常，尝试重连...")
                    try {
                        if (connectionManager.connect(config)) {
                            val sftp = connectionManager.getSftpClient()
                            sftp.write(fileInfo.remotePath).use { output ->
                                output.write(content.toByteArray(StandardCharsets.UTF_8))
                            }
                            fileInfo.lastSyncTime = System.currentTimeMillis()
                            println("[SFTP] 重连后同步成功: ${fileInfo.remotePath}")
                            showSyncSuccess(fileInfo.remotePath)
                            return@submit
                        }
                    } catch (retryEx: Exception) {
                        showSyncError(fileInfo.remotePath, "重连后同步失败: ${retryEx.message}")
                        return@submit
                    }
                }
                showSyncError(fileInfo.remotePath, e.message ?: "未知错误")
            }
        }
    }

    /**
     * 显示同步成功通知
     */
    private fun showSyncSuccess(remotePath: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // 使用 Notification 通知（更可靠）
                val notification = com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("SFTP Sync")
                    ?.createNotification(
                        "文件同步成功",
                        "已同步到 ${config.name}: $remotePath",
                        com.intellij.notification.NotificationType.INFORMATION
                    )
                
                if (notification != null) {
                    notification.notify(project)
                } else {
                    // 如果通知组不存在，使用备用方式
                    com.intellij.notification.Notification(
                        "SFTP",
                        "文件同步成功",
                        "已同步到 ${config.name}: $remotePath",
                        com.intellij.notification.NotificationType.INFORMATION
                    ).notify(project)
                }
            } catch (e: Exception) {
                // 如果通知失败，只打印日志
                println("[SFTP] 同步成功: $remotePath (通知显示失败: ${e.message})")
            }
        }
    }

    private fun showSyncError(remotePath: String, error: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(
                project,
                "同步到远程服务器失败: $error\n文件: $remotePath",
                "同步失败"
            )
        }
    }

    /**
     * 删除远程文件对应的本地缓存
     */
    fun deleteLocalCache(remotePath: String) {
        val localFile = getLocalFile(remotePath)
        
        // 如果文件在编辑器中打开，先关闭
        val localPath = localFile.absolutePath
        if (openedFiles.containsKey(localPath)) {
            ApplicationManager.getApplication().invokeLater {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(localFile)
                if (vf != null) {
                    FileEditorManager.getInstance(project).closeFile(vf)
                }
            }
            openedFiles.remove(localPath)
        }
        
        // 删除本地文件
        if (localFile.exists()) {
            localFile.delete()
            // 清理空目录
            cleanEmptyParentDirs(localFile.parentFile)
        }
    }

    /**
     * 关闭所有通过此服务打开的远程文件编辑器
     */
    fun closeAllOpenedFiles() {
        println("[SFTP] 关闭所有打开的文件，数量: ${openedFiles.size}")
        
        // 先收集所有需要关闭的文件（去重）
        val filesToClose = openedFiles.values.map { it.localFile }.distinct().toList()
        println("[SFTP] 需要关闭的文件: ${filesToClose.map { it.name }}")
        
        // 清空记录（防止dispose时再次处理）
        openedFiles.clear()
        
        if (filesToClose.isEmpty()) return
        
        ApplicationManager.getApplication().invokeLater {
            val fileEditorManager = FileEditorManager.getInstance(project)
            filesToClose.forEach { localFile ->
                try {
                    val vf = LocalFileSystem.getInstance().findFileByIoFile(localFile)
                    if (vf != null) {
                        println("[SFTP] 关闭文件: ${vf.path}")
                        fileEditorManager.closeFile(vf)
                    }
                } catch (e: Exception) {
                    println("[SFTP] 关闭文件失败: ${localFile.name}, ${e.message}")
                }
            }
        }
    }

    /**
     * 递归删除空的父目录（直到 cacheDir）
     */
    private fun cleanEmptyParentDirs(dir: File?) {
        var current = dir
        while (current != null && current != cacheDir && current.startsWith(cacheDir)) {
            if (current.isDirectory && current.list()?.isEmpty() == true) {
                current.delete()
                current = current.parentFile
            } else {
                break
            }
        }
    }

    override fun dispose() {
        // 关闭所有打开的文件
        closeAllOpenedFiles()
        executor.shutdownNow()
        messageBusConnection?.disconnect()
    }
}
