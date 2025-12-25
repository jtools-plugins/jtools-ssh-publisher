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
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.TransferTask
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.TransferTaskManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * 文件上传对话框
 */
class UploadDialog(
    private val project: Project,
    private val config: SshConfig
) : DialogWrapper(project, true), Disposable {

    private val localFileField = JBTextField()
    private val remotePathField = JBTextField(config.remoteDir)
    private val remoteFileNameField = JBTextField()  // 远程文件名（可选）

    private val preScriptsModel = UploadScriptTableModel()
    private val postScriptsModel = UploadScriptTableModel()
    private val preScriptsTable = JBTable(preScriptsModel)
    private val postScriptsTable = JBTable(postScriptsModel)

    private lateinit var tempPreScriptEditor: MultiLanguageTextField
    private lateinit var tempPostScriptEditor: MultiLanguageTextField

    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    init {
        title = "上传文件 - ${config.name} (${config.host})"
        setSize(650, 550)
        setOKButtonText("添加任务")
        setCancelButtonText("关闭")
        loadScripts()
        init()
    }

    private fun loadScripts() {
        SshConfigService.getPreScripts(config.id).forEach {
            preScriptsModel.addScript(it, it.enabled)
        }
        SshConfigService.getPostScripts(config.id).forEach {
            postScriptsModel.addScript(it, it.enabled)
        }
    }

    override fun createCenterPanel(): JComponent {
        // 创建Shell编辑器
        tempPreScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        tempPostScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        Disposer.register(disposable, tempPreScriptEditor)
        Disposer.register(disposable, tempPostScriptEditor)

        // 文件选择面板
        val filePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("本地文件:"), JPanel(BorderLayout(5, 0)).apply {
                add(localFileField, BorderLayout.CENTER)
                add(JButton("浏览", AllIcons.Actions.MenuOpen).apply {
                    addActionListener {
                        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
                            localFileField.text = vf.path
                            // 自动填充远程文件名（用户可修改）
                            if (remoteFileNameField.text.isEmpty()) {
                                remoteFileNameField.text = vf.name
                            }
                        }
                    }
                }, BorderLayout.EAST)
            })
            .addLabeledComponent(JBLabel("远程目录:"), remotePathField)
            .addLabeledComponent(JBLabel("远程文件名:"), remoteFileNameField.apply {
                toolTipText = "留空则使用本地文件名，可自定义避免中文乱码"
            })
            .panel

        // 脚本选择Tab
        val scriptTabs = JBTabbedPane().apply {
            addTab("前置脚本", createScriptSelectPanel(preScriptsTable, tempPreScriptEditor, "上传前执行"))
            addTab("后置脚本", createScriptSelectPanel(postScriptsTable, tempPostScriptEditor, "上传后执行"))
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(filePanel, BorderLayout.NORTH)
            add(scriptTabs, BorderLayout.CENTER)
        }
    }

    private fun createScriptSelectPanel(
        table: JBTable,
        tempEditor: MultiLanguageTextField,
        tempLabel: String
    ): JComponent {
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        table.rowHeight = 24
        
        // 调整列宽：选择列固定宽度，名称列适当宽度，内容预览列自动填充
        table.columnModel.getColumn(0).apply {
            preferredWidth = 40
            maxWidth = 40
            minWidth = 40
        }
        table.columnModel.getColumn(1).apply {
            preferredWidth = 150
            minWidth = 100
        }
        // 第三列（内容预览）自动填充剩余空间
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        val tablePanel = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableRemoveAction()
            .createPanel().apply {
                preferredSize = Dimension(600, 120)
            }

        val tempPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("临时脚本 ($tempLabel，不保存):"), BorderLayout.NORTH)
            add(tempEditor.apply { preferredSize = Dimension(600, 100) }, BorderLayout.CENTER)
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
            Messages.showErrorDialog(project, "请输入远程路径", "错误")
            return
        }

        // 使用自定义文件名或原文件名
        val remoteFileName = remoteFileNameField.text.trim().ifEmpty { localFile.name }
        val fullRemotePath = if (remotePath.endsWith("/")) {
            remotePath + remoteFileName
        } else {
            "$remotePath/$remoteFileName"
        }

        // 创建上传任务
        val task = TransferTask(
            type = TransferTask.TransferType.UPLOAD,
            localFile = localFile,
            remotePath = fullRemotePath,
            config = config,
            fileSize = localFile.length(),
            preScripts = preScriptsModel.getSelectedScripts(),
            postScripts = postScriptsModel.getSelectedScripts(),
            tempPreScript = tempPreScriptEditor.text.trim(),
            tempPostScript = tempPostScriptEditor.text.trim()
        )

        TransferTaskManager.addTask(task)
        Messages.showInfoMessage(project, "已添加上传任务: ${localFile.name}", "提示")
        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
    }
}

/**
 * 上传脚本表格模型
 */
class UploadScriptTableModel : AbstractTableModel() {
    private data class ScriptItem(val script: ScriptConfig, var selected: Boolean)

    private val items = mutableListOf<ScriptItem>()
    private val columns = arrayOf("选择", "名称", "内容预览")

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

    fun addScript(script: ScriptConfig, selected: Boolean) {
        items.add(ScriptItem(script, selected))
        fireTableRowsInserted(items.size - 1, items.size - 1)
    }

    fun getSelectedScripts(): List<ScriptConfig> = items.filter { it.selected }.map { it.script }
}
