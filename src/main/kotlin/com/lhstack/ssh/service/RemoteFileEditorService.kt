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
                openedFiles.remove(file.path)
            }
        })
        
        // 监听文件保存事件，同步到远程
        messageBusConnection?.subscribe(AppTopics.FILE_DOCUMENT_SYNC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                val fileInfo = openedFiles[file.path] ?: return
                syncToRemote(fileInfo, document.text)
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
                
                // 记录文件信息
                openedFiles[localFile.absolutePath] = RemoteFileInfo(remotePath, localFile)
                
                // 在 IDEA 中打开
                ApplicationManager.getApplication().invokeLater {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
                    if (vf != null) {
                        vf.refresh(false, false)
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
                if (!connectionManager.isConnected()) {
                    showSyncError(fileInfo.remotePath, "SSH 连接已断开")
                    return@submit
                }
                
                val sftp = connectionManager.getSftpClient()
                sftp.write(fileInfo.remotePath).use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                }
                fileInfo.lastSyncTime = System.currentTimeMillis()
                println("[SFTP] 文件已同步: ${fileInfo.remotePath}")
                
            } catch (e: Exception) {
                showSyncError(fileInfo.remotePath, e.message ?: "未知错误")
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
        executor.shutdownNow()
        openedFiles.clear()
        messageBusConnection?.disconnect()
    }
}
