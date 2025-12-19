package com.lhstack.ssh.view

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.UploadStatus
import com.lhstack.ssh.model.UploadTask
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 任务详情对话框
 */
class TaskDetailDialog(
    private val project: Project,
    private val task: UploadTask,
    private val logs: String
) : DialogWrapper(project, true) {

    init {
        title = "任务详情 - ${task.localFile.name}"
        setSize(600, 500)
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab("基本信息", createInfoPanel())
        tabs.addTab("脚本配置", createScriptPanel())
        tabs.addTab("执行日志", createLogPanel())
        return tabs
    }

    private fun createInfoPanel(): JComponent {
        val statusText = when (task.status) {
            UploadStatus.PENDING -> "等待中"
            UploadStatus.UPLOADING -> "上传中 (${task.progress}%)"
            UploadStatus.SUCCESS -> "上传成功"
            UploadStatus.FAILED -> "上传失败: ${task.errorMessage ?: "未知错误"}"
            UploadStatus.CANCELLED -> "已取消"
        }

        val info = buildString {
            appendLine("【服务器信息】")
            appendLine("  名称: ${task.config.name}")
            appendLine("  地址: ${task.config.host}:${task.config.port}")
            appendLine("  用户: ${task.config.username}")
            appendLine()
            appendLine("【文件信息】")
            appendLine("  本地文件: ${task.localFile.absolutePath}")
            appendLine("  文件大小: ${task.totalBytes / 1024} KB")
            appendLine("  远程目录: ${task.remotePath}")
            appendLine("  远程文件名: ${task.remoteFileName}")
            appendLine("  完整路径: ${task.fullRemotePath}")
            appendLine()
            appendLine("【上传状态】")
            appendLine("  状态: $statusText")
            appendLine("  进度: ${task.progress}%")
            appendLine("  已上传: ${task.uploadedBytes / 1024} KB / ${task.totalBytes / 1024} KB")
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            val textArea = JBTextArea(info).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            }
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    private fun createScriptPanel(): JComponent {
        val scriptInfo = buildString {
            appendLine("【前置脚本】")
            if (task.preScripts.isEmpty() && task.tempPreScript.isBlank()) {
                appendLine("  (无)")
            } else {
                task.preScripts.forEach { script ->
                    appendLine("  [${script.name}]")
                    script.content.lines().forEach { line ->
                        appendLine("    $line")
                    }
                }
                if (task.tempPreScript.isNotBlank()) {
                    appendLine("  [临时脚本]")
                    task.tempPreScript.lines().forEach { line ->
                        appendLine("    $line")
                    }
                }
            }
            appendLine()
            appendLine("【后置脚本】")
            if (task.postScripts.isEmpty() && task.tempPostScript.isBlank()) {
                appendLine("  (无)")
            } else {
                task.postScripts.forEach { script ->
                    appendLine("  [${script.name}]")
                    script.content.lines().forEach { line ->
                        appendLine("    $line")
                    }
                }
                if (task.tempPostScript.isNotBlank()) {
                    appendLine("  [临时脚本]")
                    task.tempPostScript.lines().forEach { line ->
                        appendLine("    $line")
                    }
                }
            }
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            val textArea = JBTextArea(scriptInfo).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            }
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    private fun createLogPanel(): JComponent {
        val logText = if (logs.isBlank()) "(暂无日志)" else logs
        
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            val textArea = JBTextArea(logText).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            }
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(okAction)
}
