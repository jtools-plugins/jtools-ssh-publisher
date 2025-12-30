package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.TransferTask
import com.lhstack.ssh.service.TransferTaskManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import javax.swing.*

/**
 * 传输任务管理面板（上传/下载）
 */
class TransferTaskPanel : JPanel(BorderLayout()), Disposable, TransferTaskManager.TaskListener {

    private val taskListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val taskPanels = mutableMapOf<String, TaskItemPanel>()
    private val emptyLabel = JBLabel("暂无传输任务", SwingConstants.CENTER)

    init {
        TransferTaskManager.addListener(this)

        // 工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            add(JButton("清除已完成", AllIcons.Actions.GC).apply {
                addActionListener { TransferTaskManager.clearCompletedTasks() }
            })
            add(JButton("全部停止", AllIcons.Actions.Suspend).apply {
                addActionListener { TransferTaskManager.stopAllTasks() }
            })
        }

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(taskListPanel), BorderLayout.CENTER)

        refreshList()
    }

    fun refreshList() {
        taskListPanel.removeAll()
        taskPanels.clear()

        val tasks = TransferTaskManager.getTasks()
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

    override fun onTaskAdded(task: TransferTask) {
        taskListPanel.remove(emptyLabel)
        val panel = TaskItemPanel(task)
        taskPanels[task.id] = panel
        taskListPanel.add(panel, 0)
        taskListPanel.revalidate()
        taskListPanel.repaint()
    }

    override fun onTaskUpdated(task: TransferTask) {
        taskPanels[task.id]?.updateTask(task)
    }

    override fun onTaskRemoved(task: TransferTask) {
        taskPanels.remove(task.id)?.let { taskListPanel.remove(it) }
        if (taskPanels.isEmpty()) {
            taskListPanel.add(emptyLabel)
        }
        taskListPanel.revalidate()
        taskListPanel.repaint()
    }

    override fun dispose() {
        TransferTaskManager.removeListener(this)
    }


    /**
     * 单个任务项面板
     */
    private inner class TaskItemPanel(private var task: TransferTask) : JPanel(BorderLayout(5, 5)) {

        private val statusIcon = JLabel()
        private val typeIcon = JLabel()
        private val nameLabel = JBLabel()
        private val serverLabel = JBLabel()
        private val progressBar = JProgressBar(0, 100)
        private val messageLabel = JBLabel()
        private val timeLabel = JBLabel()
        
        private val retryBtn = JLabel(AllIcons.Actions.Refresh).apply {
            toolTipText = "重试"
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

            // 顶部：类型图标 + 状态图标 + 文件名 + 服务器 + 时间
            val topPanel = JPanel(BorderLayout(5, 0)).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
                    add(typeIcon)
                    add(statusIcon)
                }, BorderLayout.WEST)
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
                    if (retryBtn.isEnabled) TransferTaskManager.retryTask(task)
                }
            })
            stopBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (stopBtn.isEnabled) TransferTaskManager.stopTask(task)
                }
            })
            detailBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    showDetailDialog()
                }
            })
            deleteBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    TransferTaskManager.removeTask(task)
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
            TransferTaskDetailDialog(task).show()
        }

        fun updateTask(task: TransferTask) {
            this.task = task

            // 类型图标
            typeIcon.icon = when (task.type) {
                TransferTask.TransferType.UPLOAD -> AllIcons.Actions.Upload
                TransferTask.TransferType.DOWNLOAD -> AllIcons.Actions.Download
            }
            typeIcon.toolTipText = when (task.type) {
                TransferTask.TransferType.UPLOAD -> "上传"
                TransferTask.TransferType.DOWNLOAD -> "下载"
            }

            nameLabel.text = task.localFile.name
            serverLabel.text = "${task.config.name} (${task.config.host})"
            progressBar.value = task.progress
            messageLabel.text = task.message
            timeLabel.text = dateFormat.format(task.createTime)

            statusIcon.icon = when (task.status) {
                TransferTask.TaskStatus.PENDING -> AllIcons.Process.Step_1
                TransferTask.TaskStatus.RUNNING -> AllIcons.Process.Step_4
                TransferTask.TaskStatus.SUCCESS -> AllIcons.General.InspectionsOK
                TransferTask.TaskStatus.FAILED -> AllIcons.General.Error
                TransferTask.TaskStatus.STOPPED -> AllIcons.Actions.Suspend
            }

            // 根据状态控制按钮可用性
            val isRunning = task.status == TransferTask.TaskStatus.RUNNING
            val isPending = task.status == TransferTask.TaskStatus.PENDING
            val canRetry = task.status == TransferTask.TaskStatus.FAILED || task.status == TransferTask.TaskStatus.STOPPED

            retryBtn.isEnabled = canRetry
            retryBtn.isVisible = canRetry
            stopBtn.isEnabled = isRunning || isPending
            stopBtn.isVisible = isRunning || isPending
        }
    }
}


/**
 * 传输任务详情对话框
 */
class TransferTaskDetailDialog(private val task: TransferTask) : DialogWrapper(true) {

    init {
        title = "任务详情 - ${task.localFile.name}"
        setSize(700, 600)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)

            add(createInfoRow("类型:", if (task.type == TransferTask.TransferType.UPLOAD) "上传" else "下载"))
            add(createInfoRow("文件名:", task.localFile.name))
            add(createInfoRow("本地路径:", task.localFile.absolutePath))
            add(createInfoRow("远程路径:", task.remotePath))
            add(createInfoRow("服务器:", "${task.config.name} (${task.config.host}:${task.config.port})"))
            add(createInfoRow("状态:", task.status.name))
            add(createInfoRow("进度:", "${task.progress}%"))
            add(createInfoRow("消息:", task.message))
            add(createInfoRow("创建时间:", dateFormat.format(task.createTime)))
        }

        val logArea = JBTextArea().apply {
            isEditable = false
            text = task.logs.joinToString("\n")
            caretPosition = 0
        }

        // 脚本内容面板（仅上传任务显示）
        val scriptPanel = if (task.type == TransferTask.TransferType.UPLOAD) {
            com.intellij.ui.components.JBTabbedPane().apply {
                // 前置脚本
                if (task.preScripts.isNotEmpty() || task.tempPreScript.isNotEmpty()) {
                    val preScriptArea = JBTextArea().apply {
                        isEditable = false
                        val sb = StringBuilder()
                        task.preScripts.forEachIndexed { index, script ->
                            if (index > 0) sb.append("\n\n")
                            sb.append("# ===== ${script.name} =====\n")
                            sb.append(script.content)
                        }
                        if (task.tempPreScript.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append("# ===== 临时前置脚本 =====\n")
                            sb.append(task.tempPreScript)
                        }
                        text = sb.toString()
                        caretPosition = 0
                    }
                    addTab("前置脚本", JBScrollPane(preScriptArea))
                }
                
                // 后置脚本
                if (task.postScripts.isNotEmpty() || task.tempPostScript.isNotEmpty()) {
                    val postScriptArea = JBTextArea().apply {
                        isEditable = false
                        val sb = StringBuilder()
                        task.postScripts.forEachIndexed { index, script ->
                            if (index > 0) sb.append("\n\n")
                            sb.append("# ===== ${script.name} =====\n")
                            sb.append(script.content)
                        }
                        if (task.tempPostScript.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append("# ===== 临时后置脚本 =====\n")
                            sb.append(task.tempPostScript)
                        }
                        text = sb.toString()
                        caretPosition = 0
                    }
                    addTab("后置脚本", JBScrollPane(postScriptArea))
                }
                
                // 执行日志
                addTab("执行日志", JBScrollPane(logArea))
            }
        } else {
            // 下载任务只显示日志
            JPanel(BorderLayout(0, 5)).apply {
                add(JBLabel("执行日志:"), BorderLayout.NORTH)
                add(JBScrollPane(logArea), BorderLayout.CENTER)
            }
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(infoPanel, BorderLayout.NORTH)
            add(scriptPanel, BorderLayout.CENTER)
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
