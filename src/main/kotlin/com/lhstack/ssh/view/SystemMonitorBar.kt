package com.lhstack.ssh.view

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.SystemInfo
import com.lhstack.ssh.service.SshConnectionManager
import java.awt.Color
import java.awt.FlowLayout
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 系统监控状态栏
 * 显示远程服务器的 CPU、内存、磁盘、进程数等信息
 */
class SystemMonitorBar(
    private val connectionManager: SshConnectionManager
) : JPanel(FlowLayout(FlowLayout.LEFT, 15, 2)), Disposable {

    private val cpuLabel = JBLabel("CPU: --")
    private val memoryLabel = JBLabel("内存: --")
    private val diskLabel = JBLabel("磁盘: --")
    private val processLabel = JBLabel("进程: --")
    
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var refreshTask: ScheduledFuture<*>? = null
    
    @Volatile
    private var running = false

    // 颜色定义
    private val normalColor: Color? = null  // 使用默认颜色
    private val warningColor = Color(255, 140, 0)  // 橙色
    private val dangerColor = Color(220, 50, 50)   // 红色

    init {
        border = JBUI.Borders.empty(2, 5)
        add(cpuLabel)
        add(createSeparator())
        add(memoryLabel)
        add(createSeparator())
        add(diskLabel)
        add(createSeparator())
        add(processLabel)
    }
    
    private fun createSeparator(): JBLabel {
        return JBLabel("|").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
    }

    /**
     * 启动监控
     */
    fun start() {
        if (running) return
        running = true
        
        // 立即获取一次数据
        scheduler.submit { collectAndUpdate() }
        
        // 每5秒刷新一次
        refreshTask = scheduler.scheduleAtFixedRate(
            { collectAndUpdate() },
            5, 5, TimeUnit.SECONDS
        )
    }

    /**
     * 停止监控
     */
    fun stop() {
        running = false
        refreshTask?.cancel(false)
        refreshTask = null
    }


    /**
     * 收集系统信息并更新显示
     */
    private fun collectAndUpdate() {
        if (!running) return
        
        try {
            if (!connectionManager.isConnected()) {
                // 未连接时，1秒后重试
                scheduler.schedule({ collectAndUpdate() }, 1, TimeUnit.SECONDS)
                return
            }
            
            val info = collectSystemInfo()
            SwingUtilities.invokeLater { updateDisplay(info) }
        } catch (e: Exception) {
            SwingUtilities.invokeLater { showError() }
        }
    }

    /**
     * 收集系统信息
     */
    private fun collectSystemInfo(): SystemInfo {
        // 使用单个命令获取所有信息，减少SSH调用次数
        val command = """
            echo "CPU:$(top -bn1 | grep "Cpu(s)" | awk '{print $2+$4}' 2>/dev/null || echo 0)"
            echo "MEM:$(free -b 2>/dev/null | grep Mem | awk '{print $3,$2}' || echo "0 0")"
            echo "DISK:$(df -B1 / 2>/dev/null | tail -1 | awk '{print $3,$2}' || echo "0 0")"
            echo "PROC:$(ps aux 2>/dev/null | wc -l || echo 0)"
        """.trimIndent()
        
        val output = connectionManager.executeCommand(command)
        return parseOutput(output)
    }

    /**
     * 解析命令输出
     */
    private fun parseOutput(output: String): SystemInfo {
        var cpuUsage = 0.0
        var memoryUsed = 0L
        var memoryTotal = 0L
        var diskUsed = 0L
        var diskTotal = 0L
        var processCount = 0
        
        output.lines().forEach { line ->
            when {
                line.startsWith("CPU:") -> {
                    cpuUsage = line.substringAfter("CPU:").trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("MEM:") -> {
                    val parts = line.substringAfter("MEM:").trim().split(" ")
                    if (parts.size >= 2) {
                        memoryUsed = parts[0].toLongOrNull() ?: 0
                        memoryTotal = parts[1].toLongOrNull() ?: 0
                    }
                }
                line.startsWith("DISK:") -> {
                    val parts = line.substringAfter("DISK:").trim().split(" ")
                    if (parts.size >= 2) {
                        diskUsed = parts[0].toLongOrNull() ?: 0
                        diskTotal = parts[1].toLongOrNull() ?: 0
                    }
                }
                line.startsWith("PROC:") -> {
                    processCount = line.substringAfter("PROC:").trim().toIntOrNull() ?: 0
                }
            }
        }
        
        return SystemInfo(cpuUsage, memoryUsed, memoryTotal, diskUsed, diskTotal, processCount)
    }

    /**
     * 更新显示
     */
    private fun updateDisplay(info: SystemInfo) {
        // CPU
        cpuLabel.text = "CPU: ${String.format("%.1f", info.cpuUsage)}%"
        cpuLabel.foreground = getColorForPercent(info.cpuUsage, 80.0, 95.0)
        
        // 内存
        val memUsed = SystemInfo.formatBytes(info.memoryUsed)
        val memTotal = SystemInfo.formatBytes(info.memoryTotal)
        memoryLabel.text = "内存: $memUsed/$memTotal (${String.format("%.0f", info.memoryUsagePercent)}%)"
        memoryLabel.foreground = getColorForPercent(info.memoryUsagePercent, 80.0, 95.0)
        
        // 磁盘
        val diskUsed = SystemInfo.formatBytes(info.diskUsed)
        val diskTotal = SystemInfo.formatBytes(info.diskTotal)
        diskLabel.text = "磁盘: $diskUsed/$diskTotal (${String.format("%.0f", info.diskUsagePercent)}%)"
        diskLabel.foreground = getColorForPercent(info.diskUsagePercent, 90.0, 95.0)
        
        // 进程
        processLabel.text = "进程: ${info.processCount}"
        processLabel.foreground = normalColor
    }

    /**
     * 根据百分比获取颜色
     */
    private fun getColorForPercent(percent: Double, warningThreshold: Double, dangerThreshold: Double): Color? {
        return when {
            percent >= dangerThreshold -> dangerColor
            percent >= warningThreshold -> warningColor
            else -> normalColor
        }
    }

    /**
     * 显示断开状态
     */
    private fun showDisconnected() {
        cpuLabel.text = "CPU: --"
        memoryLabel.text = "内存: --"
        diskLabel.text = "磁盘: --"
        processLabel.text = "进程: --"
        
        cpuLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        memoryLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        diskLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        processLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    /**
     * 显示错误状态
     */
    private fun showError() {
        cpuLabel.text = "CPU: N/A"
        memoryLabel.text = "内存: N/A"
        diskLabel.text = "磁盘: N/A"
        processLabel.text = "进程: N/A"
    }

    override fun dispose() {
        stop()
        scheduler.shutdownNow()
    }
}
