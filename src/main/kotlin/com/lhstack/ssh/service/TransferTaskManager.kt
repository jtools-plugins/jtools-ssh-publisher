package com.lhstack.ssh.service

import com.lhstack.ssh.model.TransferTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.SwingUtilities

/**
 * 传输任务管理器 - 单例
 * 统一管理上传和下载任务，每个任务使用独立的SSH连接
 */
object TransferTaskManager {

    private val tasks = CopyOnWriteArrayList<TransferTask>()
    private val listeners = CopyOnWriteArrayList<TaskListener>()
    @Volatile
    private var executor = Executors.newFixedThreadPool(5)
    private val runningFutures = ConcurrentHashMap<String, Future<*>>()
    private val runningManagers = ConcurrentHashMap<String, SshConnectionManager>()

    interface TaskListener {
        fun onTaskAdded(task: TransferTask)
        fun onTaskUpdated(task: TransferTask)
        fun onTaskRemoved(task: TransferTask)
    }

    fun addListener(listener: TaskListener) = listeners.add(listener)
    fun removeListener(listener: TaskListener) = listeners.remove(listener)
    fun getTasks(): List<TransferTask> = tasks.toList()

    fun addTask(task: TransferTask) {
        tasks.add(task)
        notifyTaskAdded(task)
        executeTask(task)
    }

    fun addTasks(taskList: List<TransferTask>) {
        taskList.forEach { task ->
            tasks.add(task)
            notifyTaskAdded(task)
            executeTask(task)
        }
    }

    fun removeTask(task: TransferTask) {
        cancelTask(task)
        tasks.remove(task)
        notifyTaskRemoved(task)
    }

    fun stopTask(task: TransferTask) {
        // 先标记任务状态为停止，让执行线程检测到后自行清理
        task.status = TransferTask.TaskStatus.STOPPED
        task.message = "正在停止..."
        notifyTaskUpdated(task)
        
        // 等待一小段时间让执行线程有机会检测到停止状态并清理
        // 如果任务还在运行，执行线程会在循环中检测到 STOPPED 状态并删除文件
        Thread {
            Thread.sleep(500)  // 给执行线程一点时间来清理
            
            // 如果执行线程还没结束，强制取消
            val future = runningFutures[task.id]
            if (future != null && !future.isDone) {
                // 如果是上传任务，需要创建新连接来删除远程文件
                if (task.type == TransferTask.TransferType.UPLOAD) {
                    deleteRemoteFileAsync(task)
                }
                // 如果是下载任务，删除本地文件
                if (task.type == TransferTask.TransferType.DOWNLOAD) {
                    try {
                        if (task.localFile.exists()) {
                            task.localFile.delete()
                            task.addLog("已删除未完成的本地文件: ${task.localFile.name}")
                        }
                    } catch (e: Exception) {
                        task.addLog("删除本地文件失败: ${e.message}")
                    }
                }
            }
            
            cancelTask(task)
            SwingUtilities.invokeLater {
                task.message = "已停止"
                task.addLog("===== 任务已停止 =====")
                notifyTaskUpdated(task)
            }
        }.start()
    }
    
    /**
     * 异步删除远程文件（使用新连接）
     */
    private fun deleteRemoteFileAsync(task: TransferTask) {
        try {
            val manager = SshConnectionManager()
            if (manager.connect(task.config)) {
                try {
                    val sftp = manager.getSftpClient()
                    sftp.remove(task.remotePath)
                    task.addLog("已删除未完成的远程文件: ${task.remotePath}")
                } catch (e: Exception) {
                    task.addLog("删除远程文件失败: ${e.message}")
                } finally {
                    manager.close()
                }
            } else {
                task.addLog("无法连接服务器删除远程文件")
            }
        } catch (e: Exception) {
            task.addLog("删除远程文件时出错: ${e.message}")
        }
    }

    private fun cancelTask(task: TransferTask) {
        runningManagers.remove(task.id)?.cancel()
        runningFutures.remove(task.id)?.cancel(true)
    }

    fun stopAllTasks() {
        val tasksToStop = tasks.filter { 
            it.status == TransferTask.TaskStatus.RUNNING || it.status == TransferTask.TaskStatus.PENDING 
        }
        
        if (tasksToStop.isEmpty()) return
        
        // 先标记所有任务为停止状态
        tasksToStop.forEach { task ->
            task.status = TransferTask.TaskStatus.STOPPED
            task.message = "正在停止..."
            notifyTaskUpdated(task)
        }
        
        // 在后台线程处理清理工作
        Thread {
            Thread.sleep(500)  // 给执行线程一点时间来清理
            
            tasksToStop.forEach { task ->
                val future = runningFutures[task.id]
                if (future != null && !future.isDone) {
                    // 如果是上传任务，需要创建新连接来删除远程文件
                    if (task.type == TransferTask.TransferType.UPLOAD) {
                        deleteRemoteFileAsync(task)
                    }
                    // 如果是下载任务，删除本地文件
                    if (task.type == TransferTask.TransferType.DOWNLOAD) {
                        try {
                            if (task.localFile.exists()) {
                                task.localFile.delete()
                                task.addLog("已删除未完成的本地文件: ${task.localFile.name}")
                            }
                        } catch (e: Exception) {
                            task.addLog("删除本地文件失败: ${e.message}")
                        }
                    }
                }
                
                cancelTask(task)
                task.message = "已停止"
                task.addLog("===== 任务已停止 =====")
            }
            
            SwingUtilities.invokeLater {
                tasksToStop.forEach { notifyTaskUpdated(it) }
            }
        }.start()
    }

    fun retryTask(task: TransferTask) {
        task.status = TransferTask.TaskStatus.PENDING
        task.progress = 0
        task.message = "等待中"
        task.logs.clear()
        task.addLog("===== 重新开始任务 =====")
        notifyTaskUpdated(task)
        executeTask(task)
    }

    fun clearCompletedTasks() {
        tasks.filter {
            it.status == TransferTask.TaskStatus.SUCCESS ||
                    it.status == TransferTask.TaskStatus.FAILED ||
                    it.status == TransferTask.TaskStatus.STOPPED
        }.forEach { task ->
            tasks.remove(task)
            notifyTaskRemoved(task)
        }
    }


    private fun executeTask(task: TransferTask) {
        ensureExecutorAvailable()
        val future = executor.submit {
            // 每个任务创建独立的SSH连接
            val manager = SshConnectionManager()
            runningManagers[task.id] = manager

            try {
                updateTask(task) {
                    status = TransferTask.TaskStatus.RUNNING
                    message = "正在连接..."
                    addLog("开始连接 ${config.host}:${config.port}")
                }

                if (!manager.connect(task.config)) {
                    if (task.status != TransferTask.TaskStatus.STOPPED) {
                        updateTask(task) {
                            status = TransferTask.TaskStatus.FAILED
                            message = "连接失败"
                            addLog("✗ 连接失败")
                        }
                    }
                    return@submit
                }
                task.addLog("✓ 连接成功")
                notifyTaskUpdated(task)

                // 执行前置脚本（仅上传任务）
                if (task.type == TransferTask.TransferType.UPLOAD) {
                    executePreScripts(task, manager)
                }

                if (task.status == TransferTask.TaskStatus.STOPPED) return@submit

                // 执行传输
                val success = when (task.type) {
                    TransferTask.TransferType.UPLOAD -> executeUpload(task, manager)
                    TransferTask.TransferType.DOWNLOAD -> executeDownload(task, manager)
                }

                if (task.status == TransferTask.TaskStatus.STOPPED) return@submit

                if (!success) {
                    updateTask(task) {
                        status = TransferTask.TaskStatus.FAILED
                        message = if (type == TransferTask.TransferType.UPLOAD) "上传失败" else "下载失败"
                        addLog("✗ ${message}")
                    }
                    return@submit
                }

                // 执行后置脚本（仅上传任务）
                if (task.type == TransferTask.TransferType.UPLOAD) {
                    executePostScripts(task, manager)
                }

                if (task.status == TransferTask.TaskStatus.STOPPED) return@submit

                updateTask(task) {
                    status = TransferTask.TaskStatus.SUCCESS
                    progress = 100
                    message = "完成"
                    addLog("===== 任务完成 =====")
                }

            } catch (e: Exception) {
                if (task.status != TransferTask.TaskStatus.STOPPED) {
                    updateTask(task) {
                        status = TransferTask.TaskStatus.FAILED
                        message = "错误: ${e.message}"
                        addLog("✗ 错误: ${e.message}")
                    }
                }
            } finally {
                manager.close()
                runningManagers.remove(task.id)
                runningFutures.remove(task.id)
            }
        }
        runningFutures[task.id] = future
    }

    private fun executePreScripts(task: TransferTask, manager: SshConnectionManager) {
        for (script in task.preScripts) {
            if (task.status == TransferTask.TaskStatus.STOPPED) return
            if (script.content.isNotEmpty()) {
                updateTask(task) {
                    message = "执行前置脚本: ${script.name}"
                    addLog("执行前置脚本: ${script.name}")
                }
                try {
                    val result = manager.executeCommand(script.content)
                    if (result.isNotBlank()) task.addLog(result)
                } catch (e: Exception) {
                    task.addLog("✗ 脚本执行失败: ${e.message}")
                }
            }
        }

        // 执行临时前置脚本
        if (task.tempPreScript.isNotEmpty() && task.status != TransferTask.TaskStatus.STOPPED) {
            updateTask(task) {
                message = "执行临时前置脚本"
                addLog("执行临时前置脚本")
            }
            try {
                val result = manager.executeCommand(task.tempPreScript)
                if (result.isNotBlank()) task.addLog(result)
            } catch (e: Exception) {
                task.addLog("✗ 脚本执行失败: ${e.message}")
            }
        }
    }

    private fun executePostScripts(task: TransferTask, manager: SshConnectionManager) {
        for (script in task.postScripts) {
            if (task.status == TransferTask.TaskStatus.STOPPED) return
            if (script.content.isNotEmpty()) {
                updateTask(task) {
                    message = "执行后置脚本: ${script.name}"
                    addLog("执行后置脚本: ${script.name}")
                }
                try {
                    val result = manager.executeCommand(script.content)
                    if (result.isNotBlank()) task.addLog(result)
                } catch (e: Exception) {
                    task.addLog("✗ 脚本执行失败: ${e.message}")
                }
            }
        }

        // 执行临时后置脚本
        if (task.tempPostScript.isNotEmpty() && task.status != TransferTask.TaskStatus.STOPPED) {
            updateTask(task) {
                message = "执行临时后置脚本"
                addLog("执行临时后置脚本")
            }
            try {
                val result = manager.executeCommand(task.tempPostScript)
                if (result.isNotBlank()) task.addLog(result)
            } catch (e: Exception) {
                task.addLog("✗ 脚本执行失败: ${e.message}")
            }
        }
    }


    private fun executeUpload(task: TransferTask, manager: SshConnectionManager): Boolean {
        updateTask(task) {
            message = "正在上传..."
            addLog("开始上传: ${localFile.name} -> $remotePath")
        }

        return try {
            val sftp = manager.getSftpClient()
            val totalSize = task.localFile.length()
            var uploaded = 0L
            var success = false

            sftp.write(task.remotePath).use { output ->
                task.localFile.inputStream().use { input ->
                    val buffer = ByteArray(32768)
                    var read: Int = 0
                    while (task.status != TransferTask.TaskStatus.STOPPED && 
                           input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        uploaded += read
                        val percent = if (totalSize > 0) (uploaded * 100 / totalSize).toInt() else 0
                        updateTask(task) {
                            progress = percent
                            message = "上传中: $percent%"
                        }
                    }
                    success = task.status != TransferTask.TaskStatus.STOPPED
                }
            }

            if (success) {
                task.addLog("✓ 上传成功")
            } else {
                // 取消时删除未完成的远程文件
                try {
                    sftp.remove(task.remotePath)
                    task.addLog("已删除未完成的远程文件")
                } catch (_: Exception) {}
            }
            success
        } catch (e: Exception) {
            task.addLog("✗ 上传错误: ${e.message}")
            false
        }
    }

    private fun executeDownload(task: TransferTask, manager: SshConnectionManager): Boolean {
        updateTask(task) {
            message = "正在下载..."
            addLog("开始下载: $remotePath -> ${localFile.absolutePath}")
        }

        return try {
            val sftp = manager.getSftpClient()
            val totalSize = task.fileSize
            var downloaded = 0L
            var success = false

            sftp.read(task.remotePath).use { input ->
                task.localFile.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var read: Int = 0
                    while (task.status != TransferTask.TaskStatus.STOPPED && 
                           input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                        updateTask(task) {
                            progress = percent
                            message = "下载中: $percent%"
                        }
                    }
                    success = task.status != TransferTask.TaskStatus.STOPPED
                }
            }

            if (success) {
                task.addLog("✓ 下载成功: ${task.localFile.absolutePath}")
            } else {
                // 取消时删除未完成的本地文件
                try {
                    task.localFile.delete()
                    task.addLog("已删除未完成的本地文件")
                } catch (_: Exception) {}
            }
            success
        } catch (e: Exception) {
            task.addLog("✗ 下载错误: ${e.message}")
            // 出错时删除未完成的本地文件
            try { task.localFile.delete() } catch (_: Exception) {}
            false
        }
    }

    private fun updateTask(task: TransferTask, block: TransferTask.() -> Unit) {
        task.block()
        notifyTaskUpdated(task)
    }

    private fun notifyTaskAdded(task: TransferTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskAdded(task) } }
    }

    private fun notifyTaskUpdated(task: TransferTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskUpdated(task) } }
    }

    private fun notifyTaskRemoved(task: TransferTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskRemoved(task) } }
    }

    fun shutdown() {
        runningManagers.values.forEach { it.cancel() }
        executor.shutdownNow()
    }
    
    /**
     * 确保线程池可用，如果已关闭则重新创建
     */
    private fun ensureExecutorAvailable() {
        if (executor.isShutdown || executor.isTerminated) {
            synchronized(this) {
                if (executor.isShutdown || executor.isTerminated) {
                    executor = Executors.newFixedThreadPool(5)
                }
            }
        }
    }
}
