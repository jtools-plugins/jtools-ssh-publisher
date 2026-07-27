package com.lhstack.ssh.service

import com.lhstack.ssh.model.AnsibleTask
import com.lhstack.ssh.model.SshConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.SwingUtilities

/**
 * Ansible 批量执行服务（内存态，关窗口即丢弃）
 */
object AnsibleRunnerService {

    private val executor = Executors.newCachedThreadPool()
    private val runningFutures = ConcurrentHashMap<String, Future<*>>()
    private val runningManagers = ConcurrentHashMap<String, SshConnectionManager>()

    interface Listener {
        fun onTaskUpdated(task: AnsibleTask)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    private fun notify(task: AnsibleTask) {
        SwingUtilities.invokeLater { listeners.forEach { it.onTaskUpdated(task) } }
    }

    fun runAll(tasks: List<AnsibleTask>) {
        tasks.forEach { task ->
            task.status = AnsibleTask.Status.PENDING
            task.logs.clear()
            val future = executor.submit { execute(task) }
            runningFutures[task.id] = future
        }
    }

    fun stopAll(tasks: List<AnsibleTask>) {
        tasks.forEach { task ->
            if (task.status == AnsibleTask.Status.RUNNING || task.status == AnsibleTask.Status.PENDING) {
                task.status = AnsibleTask.Status.STOPPED
                runningManagers.remove(task.id)?.cancel()
                runningFutures.remove(task.id)?.cancel(true)
                task.addLog("已停止")
                notify(task)
            }
        }
    }

    private fun execute(task: AnsibleTask) {
        val manager = SshConnectionManager()
        runningManagers[task.id] = manager
        try {
            task.status = AnsibleTask.Status.RUNNING
            task.addLog("正在连接 ${task.config.host}:${task.config.port}…")
            notify(task)

            if (!manager.connect(task.config)) {
                if (task.status != AnsibleTask.Status.STOPPED) {
                    task.status = AnsibleTask.Status.FAILED
                    task.addLog("✗ 连接失败: ${manager.lastErrorMessage}")
                    notify(task)
                }
                return
            }
            task.addLog("✓ 连接成功，正在执行脚本…")
            notify(task)

            val result = manager.executeCommand(task.script)
            if (task.status == AnsibleTask.Status.STOPPED) return

            if (result.isNotBlank()) task.addLog(result)
            task.status = AnsibleTask.Status.SUCCESS
            task.addLog("===== 执行完成 =====")
            notify(task)
        } catch (e: Exception) {
            if (task.status != AnsibleTask.Status.STOPPED) {
                task.status = AnsibleTask.Status.FAILED
                task.addLog("✗ 错误: ${e.message}")
                notify(task)
            }
        } finally {
            manager.close()
            runningManagers.remove(task.id)
            runningFutures.remove(task.id)
        }
    }
}
