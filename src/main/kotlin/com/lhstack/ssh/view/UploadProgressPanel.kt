package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.UploadStatus
import com.lhstack.ssh.model.UploadTask
import com.lhstack.ssh.service.UploadManager
import java.awt.*
import javax.swing.*

/**
 * 上传进度面板 - 显示所有上传任务
 */
class UploadProgressPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable, UploadManager.UploadListener {

    private val taskPanels = mutableMapOf<String, TaskItemPanel>()
    private val taskLogs = mutableMapOf<String, StringBuilder>()  // 保存每个任务的日志
    private val tasksContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val logArea = JBTextArea(6, 50).apply { 
        isEditable = false 
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val emptyLabel = JBLabel("暂无上传任务", SwingConstants.CENTER)
    
    private val uploadManager = UploadManager.getInstance()

    private var logVisible = false
    private val splitter = JBSplitter(true, 0.7f)  // 垂直分割，任务列表占70%
    private val toggleLogBtn = JButton("显示日志", AllIcons.General.ArrowDown)

    init {
        border = JBUI.Borders.empty(5)
        
        // 工具栏
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(JButton("全部开始", AllIcons.Actions.Execute).apply {
                addActionListener { uploadManager.startAllPendingTasks() }
            })
            add(JButton("清除已完成", AllIcons.Actions.GC).apply {
                addActionListener { uploadManager.clearCompletedTasks() }
            })
            add(toggleLogBtn.apply {
                addActionListener { toggleLog() }
            })
        }
        
        // 任务列表
        val taskScrollPane = JBScrollPane(tasksContainer).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }
        
        // 使用Splitter分割任务列表和日志
        splitter.firstComponent = taskScrollPane
        splitter.secondComponent = null  // 默认隐藏日志
        
        add(toolbar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        
        uploadManager.addListener(this)
        refreshTasks()
    }
    
    private fun toggleLog() {
        logVisible = !logVisible
        if (logVisible) {
            splitter.secondComponent = JBScrollPane(logArea).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
            }
            splitter.proportion = 0.7f
            toggleLogBtn.text = "隐藏日志"
            toggleLogBtn.icon = AllIcons.General.ArrowUp
        } else {
            splitter.secondComponent = null
            toggleLogBtn.text = "显示日志"
            toggleLogBtn.icon = AllIcons.General.ArrowDown
        }
        revalidate()
        repaint()
    }

    private fun refreshTasks() {
        tasksContainer.removeAll()
        taskPanels.clear()
        
        val tasks = uploadManager.getAllTasks()
        if (tasks.isEmpty()) {
            tasksContainer.add(emptyLabel)
        } else {
            tasks.forEach { task ->
                val panel = TaskItemPanel(task)
                taskPanels[task.id] = panel
                tasksContainer.add(panel)
                tasksContainer.add(Box.createVerticalStrut(5))
            }
        }
        
        tasksContainer.revalidate()
        tasksContainer.repaint()
    }

    override fun onTaskAdded(task: UploadTask) {
        if (taskPanels.isEmpty()) {
            tasksContainer.remove(emptyLabel)
        }
        val panel = TaskItemPanel(task)
        taskPanels[task.id] = panel
        tasksContainer.add(panel)
        tasksContainer.add(Box.createVerticalStrut(5))
        tasksContainer.revalidate()
        tasksContainer.repaint()
    }

    override fun onTaskUpdated(task: UploadTask) {
        taskPanels[task.id]?.updateTask(task)
    }

    override fun onTaskRemoved(task: UploadTask) {
        taskPanels.remove(task.id)?.let { panel ->
            tasksContainer.remove(panel)
            // 移除间隔
            if (tasksContainer.componentCount > 0) {
                val lastComponent = tasksContainer.getComponent(tasksContainer.componentCount - 1)
                if (lastComponent is Box.Filler) {
                    tasksContainer.remove(lastComponent)
                }
            }
        }
        taskLogs.remove(task.id)  // 清除日志
        if (taskPanels.isEmpty()) {
            tasksContainer.add(emptyLabel)
        }
        tasksContainer.revalidate()
        tasksContainer.repaint()
    }

    override fun onLogMessage(taskId: String, message: String) {
        val task = uploadManager.getAllTasks().find { it.id == taskId }
        val prefix = task?.let { "[${it.config.name}] " } ?: ""
        
        // 保存到任务日志
        taskLogs.getOrPut(taskId) { StringBuilder() }.append("$message\n")
        
        // 显示到全局日志
        logArea.append("$prefix$message\n")
        logArea.caretPosition = logArea.document.length
    }

    /**
     * 编辑任务
     */
    private fun editTask(task: UploadTask) {
        EditTaskDialog(project, task) { updatedTask ->
            uploadManager.updateTask(updatedTask)
        }.show()
    }
    
    /**
     * 查看任务详情
     */
    private fun showTaskDetail(task: UploadTask) {
        TaskDetailDialog(project, task, taskLogs[task.id]?.toString() ?: "").show()
    }

    override fun dispose() {
        uploadManager.removeListener(this)
    }

    /**
     * 单个任务项面板
     */
    private inner class TaskItemPanel(private var task: UploadTask) : JPanel(BorderLayout(5, 2)) {
        
        private val nameLabel = JBLabel(task.displayName)
        private val statusLabel = JBLabel()
        private val progressBar = JProgressBar(0, 100)
        private val sizeLabel = JBLabel()
        private val startBtn = JButton(AllIcons.Actions.Execute)
        private val editBtn = JButton(AllIcons.Actions.Edit)
        private val detailBtn = JButton(AllIcons.Actions.Preview)
        private val cancelBtn = JButton(AllIcons.Actions.Cancel)
        private val removeBtn = JButton(AllIcons.Actions.Close)
        
        init {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 70)
            
            // 顶部：名称和状态
            val topPanel = JPanel(BorderLayout()).apply {
                add(nameLabel, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.EAST)
            }
            
            // 中间：进度条
            progressBar.isStringPainted = true
            
            // 底部：大小信息和按钮
            val bottomPanel = JPanel(BorderLayout()).apply {
                add(sizeLabel, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                    startBtn.toolTipText = "开始"
                    startBtn.preferredSize = Dimension(24, 24)
                    startBtn.addActionListener { uploadManager.startTask(task.id) }
                    
                    editBtn.toolTipText = "编辑"
                    editBtn.preferredSize = Dimension(24, 24)
                    editBtn.addActionListener { editTask(task) }
                    
                    detailBtn.toolTipText = "查看详情"
                    detailBtn.preferredSize = Dimension(24, 24)
                    detailBtn.addActionListener { showTaskDetail(task) }
                    
                    cancelBtn.toolTipText = "取消"
                    cancelBtn.preferredSize = Dimension(24, 24)
                    cancelBtn.addActionListener { uploadManager.cancelTask(task.id) }
                    
                    removeBtn.toolTipText = "移除"
                    removeBtn.preferredSize = Dimension(24, 24)
                    removeBtn.addActionListener { uploadManager.removeTask(task.id) }
                    
                    add(startBtn)
                    add(editBtn)
                    add(detailBtn)
                    add(cancelBtn)
                    add(removeBtn)
                }, BorderLayout.EAST)
            }
            
            add(topPanel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
            
            updateTask(task)
        }
        
        fun updateTask(task: UploadTask) {
            this.task = task
            nameLabel.text = task.displayName
            progressBar.value = task.progress
            progressBar.string = "${task.progress}%"
            
            val uploadedKB = task.uploadedBytes / 1024
            val totalKB = task.totalBytes / 1024
            sizeLabel.text = "${uploadedKB}KB / ${totalKB}KB"
            
            when (task.status) {
                UploadStatus.PENDING -> {
                    statusLabel.text = "等待中"
                    statusLabel.foreground = JBColor.GRAY
                    startBtn.isEnabled = true
                    editBtn.isEnabled = true
                    cancelBtn.isEnabled = false
                }
                UploadStatus.UPLOADING -> {
                    statusLabel.text = "上传中"
                    statusLabel.foreground = JBColor.BLUE
                    startBtn.isEnabled = false
                    editBtn.isEnabled = false
                    cancelBtn.isEnabled = true
                }
                UploadStatus.SUCCESS -> {
                    statusLabel.text = "✓ 成功"
                    statusLabel.foreground = JBColor(Color(0, 128, 0), Color(0, 180, 0))
                    startBtn.isEnabled = false
                    editBtn.isEnabled = false
                    cancelBtn.isEnabled = false
                    progressBar.foreground = JBColor(Color(0, 128, 0), Color(0, 180, 0))
                }
                UploadStatus.FAILED -> {
                    statusLabel.text = "✗ 失败: ${task.errorMessage ?: "未知错误"}"
                    statusLabel.foreground = JBColor.RED
                    startBtn.isEnabled = true
                    editBtn.isEnabled = true
                    cancelBtn.isEnabled = false
                }
                UploadStatus.CANCELLED -> {
                    statusLabel.text = "已取消"
                    statusLabel.foreground = JBColor.ORANGE
                    startBtn.isEnabled = true
                    editBtn.isEnabled = true
                    cancelBtn.isEnabled = false
                }
            }
        }
    }
}
