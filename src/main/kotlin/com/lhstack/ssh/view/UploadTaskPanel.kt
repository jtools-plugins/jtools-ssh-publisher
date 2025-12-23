package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.UploadTask
import com.lhstack.ssh.service.UploadTaskManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import javax.swing.*

/**
 * 上传任务管理面板
 */
class UploadTaskPanel : JPanel(BorderLayout()), Disposable, UploadTaskManager.TaskListener {

    private val taskListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val taskPanels = mutableMapOf<String, TaskItemPanel>()
    private val emptyLabel = JBLabel("暂无上传任务", SwingConstants.CENTER)

    init {
        UploadTaskManager.addListener(this)

        // 工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            add(JButton("清除已完成", AllIcons.Actions.GC).apply {
                addActionListener { UploadTaskManager.clearCompletedTasks() }
            })
            add(JButton("全部停止", AllIcons.Actions.Suspend).apply {
                addActionListener { UploadTaskManager.stopAllTasks() }
            })
        }

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(taskListPanel), BorderLayout.CENTER)

        refreshTasks()
    }

    private fun refreshTasks() {
        taskListPanel.removeAll()
        taskPanels.clear()

        val tasks = UploadTaskManager.getTasks()
        if (tasks.isEmpty()) {
            taskListPanel.add(emptyLabel)
        } else {
            tasks.sortedByDescending { it.createTime }.forEach { task ->
                val panel = TaskItemPanel(task)
                taskPanels[task.id] = panel
                taskListPanel.add(panel)
            }
        }
        taskListPanel.revalidate()
        taskListPanel.repaint()
    }

    override fun onTaskAdded(task: UploadTask) {
        taskListPanel.remove(emptyLabel)
        val panel = TaskItemPanel(task)
        taskPanels[task.id] = panel
        taskListPanel.add(panel, 0)
        taskListPanel.revalidate()
        taskListPanel.repaint()
    }

    override fun onTaskUpdated(task: UploadTask) {
        taskPanels[task.id]?.updateTask(task)
    }

    override fun onTaskRemoved(task: UploadTask) {
        taskPanels.remove(task.id)?.let { taskListPanel.remove(it) }
        if (taskPanels.isEmpty()) {
            taskListPanel.add(emptyLabel)
        }
        taskListPanel.revalidate()
        taskListPanel.repaint()
    }

    override fun dispose() {
        UploadTaskManager.removeListener(this)
    }

    /**
     * 单个任务项面板
     */
    private inner class TaskItemPanel(private var task: UploadTask) : JPanel(BorderLayout(5, 5)) {

        private val statusIcon = JLabel()
        private val nameLabel = JBLabel()
        private val serverLabel = JBLabel()
        private val progressBar = JProgressBar(0, 100)
        private val messageLabel = JBLabel()
        private val timeLabel = JBLabel()
        
        private val retryBtn = JLabel(AllIcons.Actions.Refresh).apply {
            toolTipText = "重新上传"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        private val stopBtn = JLabel(AllIcons.Actions.Suspend).apply {
            toolTipText = "停止"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        private val detailBtn = JLabel(AllIcons.Actions.Preview).apply {
            toolTipText = "查看详情"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        private val deleteBtn = JLabel(AllIcons.Actions.GC).apply {
            toolTipText = "删除"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        private val dateFormat = SimpleDateFormat("HH:mm:ss")

        init {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0),
                JBUI.Borders.empty(8)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 80)

            // 顶部：状态图标 + 文件名 + 服务器 + 时间
            val topPanel = JPanel(BorderLayout(5, 0)).apply {
                add(statusIcon, BorderLayout.WEST)
                add(JPanel(BorderLayout()).apply {
                    add(nameLabel, BorderLayout.WEST)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                        add(serverLabel)
                        add(timeLabel.apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND })
                    }, BorderLayout.EAST)
                }, BorderLayout.CENTER)
            }

            // 中间：进度条 + 消息
            val middlePanel = JPanel(BorderLayout(10, 0)).apply {
                add(progressBar.apply { preferredSize = Dimension(200, 16) }, BorderLayout.CENTER)
                add(messageLabel, BorderLayout.EAST)
            }

            // 操作按钮
            val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                add(retryBtn)
                add(stopBtn)
                add(detailBtn)
                add(deleteBtn)
            }

            // 绑定事件
            retryBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (retryBtn.isEnabled) UploadTaskManager.retryTask(task)
                }
            })
            stopBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (stopBtn.isEnabled) UploadTaskManager.stopTask(task)
                }
            })
            detailBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    showDetailDialog()
                }
            })
            deleteBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    UploadTaskManager.removeTask(task)
                }
            })

            val infoPanel = JPanel(BorderLayout(0, 5)).apply {
                add(topPanel, BorderLayout.NORTH)
                add(JPanel(BorderLayout()).apply {
                    add(middlePanel, BorderLayout.CENTER)
                    add(actionPanel, BorderLayout.EAST)
                }, BorderLayout.CENTER)
            }

            add(infoPanel, BorderLayout.CENTER)
            updateTask(task)
        }

        private fun showDetailDialog() {
            TaskDetailDialog(task).show()
        }

        fun updateTask(task: UploadTask) {
            this.task = task

            nameLabel.text = task.localFile.name
            serverLabel.text = "${task.config.name} (${task.config.host})"
            progressBar.value = task.progress
            messageLabel.text = task.message
            timeLabel.text = dateFormat.format(task.createTime)

            statusIcon.icon = when (task.status) {
                UploadTask.TaskStatus.PENDING -> AllIcons.Process.Step_1
                UploadTask.TaskStatus.RUNNING -> AllIcons.Process.Step_4
                UploadTask.TaskStatus.SUCCESS -> AllIcons.General.InspectionsOK
                UploadTask.TaskStatus.FAILED -> AllIcons.General.Error
                UploadTask.TaskStatus.STOPPED -> AllIcons.Actions.Suspend
            }

            // 根据状态控制按钮可用性
            val isRunning = task.status == UploadTask.TaskStatus.RUNNING
            val isPending = task.status == UploadTask.TaskStatus.PENDING
            val canRetry = task.status == UploadTask.TaskStatus.FAILED || task.status == UploadTask.TaskStatus.STOPPED

            retryBtn.isEnabled = canRetry
            retryBtn.isVisible = canRetry
            stopBtn.isEnabled = isRunning || isPending
            stopBtn.isVisible = isRunning || isPending
        }
    }
}


/**
 * 任务详情对话框
 */
class TaskDetailDialog(private val task: UploadTask) : DialogWrapper(true) {

    init {
        title = "任务详情 - ${task.localFile.name}"
        setSize(600, 450)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)

            add(createInfoRow("文件名:", task.localFile.name))
            add(createInfoRow("本地路径:", task.localFile.absolutePath))
            add(createInfoRow("远程路径:", task.remotePath))
            add(createInfoRow("服务器:", "${task.config.name} (${task.config.host}:${task.config.port})"))
            add(createInfoRow("状态:", task.status.name))
            add(createInfoRow("进度:", "${task.progress}%"))
            add(createInfoRow("消息:", task.message))
            add(createInfoRow("创建时间:", dateFormat.format(task.createTime)))
            add(createInfoRow("前置脚本:", task.preScripts.joinToString(", ") { it.name }.ifEmpty { "无" }))
            add(createInfoRow("后置脚本:", task.postScripts.joinToString(", ") { it.name }.ifEmpty { "无" }))
        }

        val logArea = JBTextArea().apply {
            isEditable = false
            text = task.logs.joinToString("\n")
            caretPosition = 0
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(infoPanel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 5)).apply {
                add(JBLabel("执行日志:"), BorderLayout.NORTH)
                add(JBScrollPane(logArea), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }

    private fun createInfoRow(label: String, value: String): JPanel {
        return JPanel(BorderLayout(10, 0)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 25)
            add(JBLabel(label).apply { 
                preferredSize = Dimension(80, 20)
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }, BorderLayout.WEST)
            add(JBLabel(value), BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
