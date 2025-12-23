package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.UploadTask
import com.lhstack.ssh.service.SshConfigService
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * 编辑上传任务对话框 - 支持编辑文件路径和脚本
 */
class EditTaskDialog(
    private val project: Project,
    private val task: UploadTask,
    private val onSave: (UploadTask) -> Unit
) : DialogWrapper(project, true), Disposable {

    private val localFileField = JBTextField(task.localFile.path)
    private val remotePathField = JBTextField(task.remotePath)

    private val preScriptsModel = EditScriptTableModel(task.preScripts)
    private val postScriptsModel = EditScriptTableModel(task.postScripts)
    private val preScriptsTable = JBTable(preScriptsModel)
    private val postScriptsTable = JBTable(postScriptsModel)

    private lateinit var tempPreScriptEditor: MultiLanguageTextField
    private lateinit var tempPostScriptEditor: MultiLanguageTextField

    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    init {
        title = "编辑上传任务"
        setSize(650, 550)
        setOKButtonText("保存")
        init()
    }

    override fun createCenterPanel(): JComponent {
        tempPreScriptEditor = MultiLanguageTextField(shellFileType, project, task.tempPreScript, isLineNumbersShown = true)
        tempPostScriptEditor = MultiLanguageTextField(shellFileType, project, task.tempPostScript, isLineNumbersShown = true)
        Disposer.register(disposable, tempPreScriptEditor)
        Disposer.register(disposable, tempPostScriptEditor)

        // 文件选择面板
        val filePanel = JPanel(BorderLayout(5, 0)).apply {
            add(localFileField, BorderLayout.CENTER)
            add(JButton("浏览", AllIcons.Actions.MenuOpen).apply {
                addActionListener {
                    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
                        localFileField.text = vf.path
                    }
                }
            }, BorderLayout.EAST)
        }

        val infoLabel = JBLabel("服务器: ${task.config.name} (${task.config.host})").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        val basicPanel = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addVerticalGap(10)
            .addLabeledComponent(JBLabel("本地文件:"), filePanel)
            .addLabeledComponent(JBLabel("远程目录:"), remotePathField)
            .panel

        // 脚本选择Tab
        val scriptTabs = JBTabbedPane().apply {
            addTab("前置脚本", createScriptPanel(preScriptsTable, tempPreScriptEditor, "上传前执行"))
            addTab("后置脚本", createScriptPanel(postScriptsTable, tempPostScriptEditor, "上传后执行"))
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(basicPanel, BorderLayout.NORTH)
            add(scriptTabs, BorderLayout.CENTER)
        }
    }

    private fun createScriptPanel(
        table: JBTable,
        tempEditor: MultiLanguageTextField,
        tempLabel: String
    ): JComponent {
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        table.rowHeight = 24

        table.columnModel.getColumn(0).apply {
            preferredWidth = 40
            maxWidth = 40
            minWidth = 40
        }
        table.columnModel.getColumn(1).preferredWidth = 150
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        val tablePanel = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableRemoveAction()
            .createPanel().apply {
                preferredSize = Dimension(600, 100)
            }

        val tempPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("临时脚本 ($tempLabel):"), BorderLayout.NORTH)
            add(tempEditor.apply { preferredSize = Dimension(600, 80) }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, 10)).apply {
            add(tablePanel, BorderLayout.NORTH)
            add(tempPanel, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val localPath = localFileField.text.trim()
        val remotePath = remotePathField.text.trim()

        if (localPath.isEmpty()) {
            Messages.showErrorDialog(project, "请选择本地文件", "错误")
            return
        }

        val localFile = File(localPath)
        if (!localFile.exists()) {
            Messages.showErrorDialog(project, "本地文件不存在", "错误")
            return
        }

        if (remotePath.isEmpty()) {
            Messages.showErrorDialog(project, "请输入远程目录", "错误")
            return
        }

        // 创建更新后的任务
        val updatedTask = task.copy(
            localFile = localFile,
            remotePath = remotePath,
            preScripts = preScriptsModel.getSelectedScripts(),
            postScripts = postScriptsModel.getSelectedScripts(),
            tempPreScript = tempPreScriptEditor.text.trim(),
            tempPostScript = tempPostScriptEditor.text.trim()
        )

        onSave(updatedTask)
        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
    }
}

/**
 * 编辑脚本表格模型
 */
class EditScriptTableModel(initialScripts: List<ScriptConfig>) : AbstractTableModel() {
    private data class ScriptItem(val script: ScriptConfig, var selected: Boolean)

    private val items = mutableListOf<ScriptItem>()
    private val columns = arrayOf("选择", "名称", "内容预览")

    init {
        initialScripts.forEach { items.add(ScriptItem(it, true)) }
    }

    override fun getRowCount() = items.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = items[rowIndex]
        return when (columnIndex) {
            0 -> item.selected
            1 -> item.script.name
            2 -> item.script.content.replace("\n", " ").take(60)
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0 && aValue is Boolean) {
            items[rowIndex].selected = aValue
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    fun getSelectedScripts(): List<ScriptConfig> = items.filter { it.selected }.map { it.script }
}
