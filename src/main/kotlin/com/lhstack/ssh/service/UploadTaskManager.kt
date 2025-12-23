package com.lhstack.ssh.service

import com.lhstack.ssh.model.UploadTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.SwingUtilities

/**
 * 上传任务管理器 - 单例
 */
object UploadTaskManager {

    private val tasks = CopyOnWriteArrayList<UploadTask>()
    private val listeners = CopyOnWriteArrayList<TaskListener>()
    private val executor = Executors.newFixedThreadPool(3)
    private val runningFutures = ConcurrentHashMap<String, Future<*>>()
    private val runningManagers = ConcurrentHashMap<String, SshConnectionManager>()

    interface TaskListener {
        fun onTaskAdded(task: UploadTask)
        fun onTaskUpdated(task: UploadTask)
        fun onTaskRemoved(task: UploadTask)
    }

    fun addListener(listener: TaskListener) = listeners.add(listener)
    fun removeListener(listener: TaskListener) = listeners.remove(listener)
    fun getTasks(): List<UploadTask> = tasks.toList()

    fun addTask(task: UploadTask) {
        tasks.add(task)
        notifyTaskAdded(task)
        executeTask(task)
    }

    fun addTasks(taskList: List<UploadTask>) {
        taskList.forEach { task ->
            tasks.add(task)
            notifyTaskAdded(task)
            executeTask(task)
        }
    }

    fun removeTask(task: UploadTask) {
        cancelTask(task)
        tasks.remove(task)
        notifyTaskRemoved(task)
    }

    fun stopTask(task: UploadTask) {
        cancelTask(task)
        updateTask(task) {
            status = UploadTask.TaskStatus.STOPPED
            message = "已停止"
            addLog("===== 任务已停止 =====")
        }
    }

    private fun cancelTask(task: UploadTask) {
        runningManagers.remove(task.id)?.cancel()
        runningFutures.remove(task.id)?.cancel(true)
    }

    fun stopAllTasks() {
        tasks.filter { it.status == UploadTask.TaskStatus.RUNNING || it.status == UploadTask.TaskStatus.PENDING }
            .forEach { stopTask(it) }
    }

    fun retryTask(task: UploadTask) {
        task.status = UploadTask.TaskStatus.PENDING
        task.progress = 0
        task.message = "等待中"
        task.logs.clear()
        task.addLog("===== 重新开始任务 =====")
        notifyTaskUpdated(task)
        executeTask(task)
    }

    fun clearCompletedTasks() {
        tasks.filter {
            it.status == UploadTask.TaskStatus.SUCCESS ||
                    it.status == UploadTask.TaskStatus.FAILED ||
                    it.status == UploadTask.TaskStatus.STOPPED
        }.forEach { task ->
            tasks.remove(task)
            notifyTaskRemoved(task)
        }
    }

    private fun executeTask(task: UploadTask) {
        val future = executor.submit {
            val manager = SshConnectionManager()
            runningManagers[task.id] = manager

            try {
                updateTask(task) {
                    status = UploadTask.TaskStatus.RUNNING
                    message = "正在连接..."
                    addLog("开始连接 ${config.host}:${config.port}")
                }

                if (!manager.connect(task.config)) {
                    if (task.status != UploadTask.TaskStatus.STOPPED) {
                        updateTask(task) {
                            status = UploadTask.TaskStatus.FAILED
                            message = "连接失败"
                            addLog("✗ 连接失败")
                        }
                    }
                    return@submit
                }
                task.addLog("✓ 连接成功")
                notifyTaskUpdated(task)

                // 执行前置脚本
                for (script in task.preScripts) {
                    if (task.status == UploadTask.TaskStatus.STOPPED) return@submit
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
                if (task.tempPreScript.isNotEmpty() && task.status != UploadTask.TaskStatus.STOPPED) {
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

                // 上传文件
                if (task.status == UploadTask.TaskStatus.STOPPED) return@submit
                updateTask(task) {
                    message = "正在上传..."
                    addLog("开始上传: ${localFile.name} -> $remotePath")
                }

                val success = manager.uploadFile(task.localFile, task.remotePath) { uploaded, total ->
                    if (task.status != UploadTask.TaskStatus.STOPPED) {
                        val percent = (uploaded * 100 / total).toInt()
                        updateTask(task) {
                            progress = percent
                            message = "上传中: $percent%"
                        }
                    }
                }

                if (task.status == UploadTask.TaskStatus.STOPPED) return@submit

                if (!success) {
                    updateTask(task) {
                        status = UploadTask.TaskStatus.FAILED
                        message = "上传失败"
                        addLog("✗ 上传失败")
                    }
                    return@submit
                }
                task.addLog("✓ 上传成功")
                notifyTaskUpdated(task)

                // 执行后置脚本
                for (script in task.postScripts) {
                    if (task.status == UploadTask.TaskStatus.STOPPED) return@submit
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
                if (task.tempPostScript.isNotEmpty() && task.status != UploadTask.TaskStatus.STOPPED) {
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

                if (task.status == UploadTask.TaskStatus.STOPPED) return@submit

                updateTask(task) {
                    status = UploadTask.TaskStatus.SUCCESS
                    progress = 100
                    message = "完成"
                    addLog("===== 任务完成 =====")
                }

            } catch (e: Exception) {
                if (task.status != UploadTask.TaskStatus.STOPPED) {
                    updateTask(task) {
                        status = UploadTask.TaskStatus.FAILED
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

    private fun updateTask(task: UploadTask, block: UploadTask.() -> Unit) {
        task.block()
        notifyTaskUpdated(task)
    }

    private fun notifyTaskAdded(task: UploadTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskAdded(task) } }
    }

    private fun notifyTaskUpdated(task: UploadTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskUpdated(task) } }
    }

    private fun notifyTaskRemoved(task: UploadTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskRemoved(task) } }
    }

    fun shutdown() {
        runningManagers.values.forEach { it.cancel() }
        executor.shutdownNow()
    }
}
