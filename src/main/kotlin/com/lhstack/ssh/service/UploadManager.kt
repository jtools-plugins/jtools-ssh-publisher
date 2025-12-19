package com.lhstack.ssh.service

import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.UploadStatus
import com.lhstack.ssh.model.UploadTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.SwingUtilities

/**
 * 上传任务管理器 - 支持多SSH客户端并行上传
 */
class UploadManager {
    
    // 每个SSH配置的连接池
    private val connectionPools = ConcurrentHashMap<String, MutableList<SshConnectionManager>>()
    
    // 任务执行器 - 支持并行上传
    private val executor = Executors.newFixedThreadPool(10)
    
    // 任务列表
    private val tasks = ConcurrentHashMap<String, UploadTask>()
    private val taskFutures = ConcurrentHashMap<String, Future<*>>()
    
    // 监听器
    private val listeners = mutableListOf<UploadListener>()
    
    interface UploadListener {
        fun onTaskAdded(task: UploadTask)
        fun onTaskUpdated(task: UploadTask)
        fun onTaskRemoved(task: UploadTask)
        fun onLogMessage(taskId: String, message: String)
    }
    
    fun addListener(listener: UploadListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: UploadListener) {
        listeners.remove(listener)
    }
    
    /**
     * 添加上传任务
     */
    fun addTask(task: UploadTask): UploadTask {
        tasks[task.id] = task
        notifyTaskAdded(task)
        return task
    }
    
    /**
     * 开始上传任务
     */
    fun startTask(taskId: String) {
        val task = tasks[taskId] ?: return
        if (task.status != UploadStatus.PENDING && task.status != UploadStatus.FAILED) return
        
        task.status = UploadStatus.UPLOADING
        task.progress = 0
        task.errorMessage = null
        notifyTaskUpdated(task)
        
        val future = executor.submit {
            executeUpload(task)
        }
        taskFutures[taskId] = future
    }
    
    /**
     * 开始所有等待中的任务
     */
    fun startAllPendingTasks() {
        tasks.values.filter { it.status == UploadStatus.PENDING }.forEach {
            startTask(it.id)
        }
    }
    
    /**
     * 取消任务
     */
    fun cancelTask(taskId: String) {
        val task = tasks[taskId] ?: return
        taskFutures[taskId]?.cancel(true)
        task.status = UploadStatus.CANCELLED
        notifyTaskUpdated(task)
    }
    
    /**
     * 更新任务
     */
    fun updateTask(task: UploadTask) {
        if (tasks.containsKey(task.id)) {
            // 只允许更新非上传中的任务
            val existing = tasks[task.id]
            if (existing?.status == UploadStatus.UPLOADING) return
            
            task.status = UploadStatus.PENDING
            task.progress = 0
            task.uploadedBytes = 0
            task.errorMessage = null
            tasks[task.id] = task
            notifyTaskUpdated(task)
        }
    }
    
    /**
     * 移除任务
     */
    fun removeTask(taskId: String) {
        val task = tasks.remove(taskId) ?: return
        taskFutures.remove(taskId)?.cancel(true)
        notifyTaskRemoved(task)
    }
    
    /**
     * 清除已完成的任务
     */
    fun clearCompletedTasks() {
        val completed = tasks.values.filter { 
            it.status == UploadStatus.SUCCESS || 
            it.status == UploadStatus.FAILED || 
            it.status == UploadStatus.CANCELLED 
        }
        completed.forEach { removeTask(it.id) }
    }
    
    /**
     * 获取所有任务
     */
    fun getAllTasks(): List<UploadTask> = tasks.values.toList()

    
    /**
     * 执行上传
     */
    private fun executeUpload(task: UploadTask) {
        var manager: SshConnectionManager? = null
        try {
            log(task.id, "正在连接 ${task.config.host}:${task.config.port}...")
            
            manager = getOrCreateConnection(task.config)
            if (manager == null || !manager.isConnected()) {
                throw Exception("连接失败，请检查配置")
            }
            log(task.id, "✓ 连接成功")
            
            // 执行前置脚本
            executeScripts(task, manager, task.preScripts, "前置")
            if (task.tempPreScript.isNotBlank()) {
                log(task.id, "执行临时前置脚本...")
                val result = manager.executeCommand(task.tempPreScript)
                if (result.isNotBlank()) log(task.id, result)
            }
            
            // 检查是否被取消
            if (Thread.currentThread().isInterrupted || task.status == UploadStatus.CANCELLED) {
                throw InterruptedException("任务已取消")
            }
            
            // 上传文件
            log(task.id, "开始上传: ${task.localFile.name} -> ${task.remoteFileName} (${task.totalBytes / 1024}KB)")
            
            val success = manager.uploadFile(task.localFile, task.fullRemotePath) { uploaded, total ->
                task.uploadedBytes = uploaded
                task.progress = (uploaded * 100 / total).toInt()
                notifyTaskUpdated(task)
            }
            
            if (!success) {
                throw Exception("上传失败")
            }
            log(task.id, "✓ 上传成功: ${task.fullRemotePath}")
            
            // 执行后置脚本
            executeScripts(task, manager, task.postScripts, "后置")
            if (task.tempPostScript.isNotBlank()) {
                log(task.id, "执行临时后置脚本...")
                val result = manager.executeCommand(task.tempPostScript)
                if (result.isNotBlank()) log(task.id, result)
            }
            
            task.status = UploadStatus.SUCCESS
            task.progress = 100
            log(task.id, "===== 完成 =====")
            notifyTaskUpdated(task)
            
        } catch (e: InterruptedException) {
            task.status = UploadStatus.CANCELLED
            task.errorMessage = "已取消"
            log(task.id, "✗ 任务已取消")
            notifyTaskUpdated(task)
        } catch (e: Exception) {
            task.status = UploadStatus.FAILED
            task.errorMessage = e.message
            log(task.id, "✗ 错误: ${e.message}")
            notifyTaskUpdated(task)
        } finally {
            manager?.let { returnConnection(task.config, it) }
        }
    }
    
    private fun executeScripts(task: UploadTask, manager: SshConnectionManager, scripts: List<com.lhstack.ssh.model.ScriptConfig>, type: String) {
        scripts.forEach { script ->
            if (script.content.isNotEmpty()) {
                log(task.id, "执行${type}脚本: ${script.name}")
                try {
                    val result = manager.executeCommand(script.content)
                    if (result.isNotBlank()) log(task.id, result)
                } catch (e: Exception) {
                    log(task.id, "✗ 脚本执行失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 获取或创建连接
     */
    @Synchronized
    private fun getOrCreateConnection(config: SshConfig): SshConnectionManager? {
        val pool = connectionPools.getOrPut(config.id) { mutableListOf() }
        
        // 尝试复用空闲连接
        val available = pool.find { it.isConnected() }
        if (available != null) {
            pool.remove(available)
            return available
        }
        
        // 创建新连接
        val manager = SshConnectionManager()
        return if (manager.connect(config)) manager else null
    }
    
    /**
     * 归还连接到池
     */
    @Synchronized
    private fun returnConnection(config: SshConfig, manager: SshConnectionManager) {
        if (manager.isConnected()) {
            val pool = connectionPools.getOrPut(config.id) { mutableListOf() }
            if (pool.size < 3) { // 每个配置最多保留3个连接
                pool.add(manager)
                return
            }
        }
        manager.close()
    }
    
    private fun log(taskId: String, message: String) {
        SwingUtilities.invokeLater {
            listeners.forEach { it.onLogMessage(taskId, message) }
        }
    }
    
    private fun notifyTaskAdded(task: UploadTask) {
        SwingUtilities.invokeLater {
            listeners.forEach { it.onTaskAdded(task) }
        }
    }
    
    private fun notifyTaskUpdated(task: UploadTask) {
        SwingUtilities.invokeLater {
            listeners.forEach { it.onTaskUpdated(task) }
        }
    }
    
    private fun notifyTaskRemoved(task: UploadTask) {
        SwingUtilities.invokeLater {
            listeners.forEach { it.onTaskRemoved(task) }
        }
    }
    
    /**
     * 关闭管理器
     */
    fun shutdown() {
        executor.shutdownNow()
        connectionPools.values.flatten().forEach { it.close() }
        connectionPools.clear()
        tasks.clear()
    }
    
    companion object {
        @Volatile
        private var instance: UploadManager? = null
        
        fun getInstance(): UploadManager {
            return instance ?: synchronized(this) {
                instance ?: UploadManager().also { instance = it }
            }
        }
    }
}
