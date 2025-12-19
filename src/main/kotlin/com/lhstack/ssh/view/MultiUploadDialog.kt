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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.UploadTask
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.UploadManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * 多文件上传对话框
 */
class MultiUploadDialog(
    private val project: Project,
    private val config: SshConfig
) : DialogWrapper(project, true), Disposable {

    private val remotePathField = JBTextField(config.remoteDir)
    private val filesModel = FileListTableModel()
    private val filesTable = JBTable(filesModel)

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
        setSize(700, 650)
        setOKButtonText("添加到上传队列")
        setCancelButtonText("关闭")
        loadScripts()
        init()
    }

    private fun loadScripts() {
        SshConfigService.getPreScripts(config.id).forEach {
            preScriptsModel.addScript(it, false)  // 默认不选中
        }
        SshConfigService.getPostScripts(config.id).forEach {
            postScriptsModel.addScript(it, false)  // 默认不选中
        }
    }

    override fun createCenterPanel(): JComponent {
        tempPreScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        tempPostScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        Disposer.register(disposable, tempPreScriptEditor)
        Disposer.register(disposable, tempPostScriptEditor)

        // 文件列表面板
        filesTable.setShowGrid(false)
        filesTable.rowHeight = 24
        filesTable.columnModel.getColumn(0).preferredWidth = 250
        filesTable.columnModel.getColumn(1).preferredWidth = 150
        filesTable.columnModel.getColumn(2).preferredWidth = 80

        val filesPanel = ToolbarDecorator.createDecorator(filesTable)
            .setAddAction {
                val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                descriptor.title = "选择要上传的文件"
                FileChooser.chooseFiles(descriptor, project, null) { files ->
                    files.forEach { vf ->
                        filesModel.addFile(File(vf.path))
                    }
                }
            }
            .setRemoveAction {
                val selected = filesTable.selectedRows.sortedDescending()
                selected.forEach { filesModel.removeFile(it) }
            }
            .createPanel().apply {
                preferredSize = Dimension(650, 150)
            }

        // 远程路径
        val pathPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("远程目录:"), remotePathField)
            .panel

        // 脚本选择Tab
        val scriptTabs = JBTabbedPane().apply {
            addTab("前置脚本", createScriptSelectPanel(preScriptsTable, tempPreScriptEditor, "上传前执行"))
            addTab("后置脚本", createScriptSelectPanel(postScriptsTable, tempPostScriptEditor, "上传后执行"))
        }

        // 提示信息
        val tipLabel = JBLabel("提示: 文件将添加到上传队列，可在上传管理面板查看进度").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(JPanel(BorderLayout(0, 5)).apply {
                add(JBLabel("选择文件:"), BorderLayout.NORTH)
                add(filesPanel, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 10)).apply {
                add(pathPanel, BorderLayout.NORTH)
                add(scriptTabs, BorderLayout.CENTER)
                add(tipLabel, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
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
            add(JBLabel("临时脚本 ($tempLabel，不保存):"), BorderLayout.NORTH)
            add(tempEditor.apply { preferredSize = Dimension(600, 80) }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, 10)).apply {
            add(tablePanel, BorderLayout.NORTH)
            add(tempPanel, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val files = filesModel.getFiles()
        val remotePath = remotePathField.text.trim()

        if (files.isEmpty()) {
            Messages.showErrorDialog(project, "请选择要上传的文件", "错误")
            return
        }

        if (remotePath.isEmpty()) {
            Messages.showErrorDialog(project, "请输入远程目录", "错误")
            return
        }

        // 验证文件
        val invalidFiles = files.filter { !it.first.exists() }
        if (invalidFiles.isNotEmpty()) {
            Messages.showErrorDialog(project, "以下文件不存在:\n${invalidFiles.joinToString("\n") { it.first.path }}", "错误")
            return
        }

        val uploadManager = UploadManager.getInstance()
        val preScripts = preScriptsModel.getSelectedScripts()
        val postScripts = postScriptsModel.getSelectedScripts()
        val tempPreScript = tempPreScriptEditor.text.trim()
        val tempPostScript = tempPostScriptEditor.text.trim()

        // 创建上传任务
        files.forEach { (file, remoteName) ->
            val task = UploadTask(
                localFile = file,
                remotePath = remotePath,
                remoteFileName = remoteName,
                config = config,
                preScripts = preScripts,
                postScripts = postScripts,
                tempPreScript = tempPreScript,
                tempPostScript = tempPostScript
            )
            uploadManager.addTask(task)
        }

        Messages.showInfoMessage(
            project,
            "已添加 ${files.size} 个文件到上传队列\n可在上传管理面板查看进度并开始上传",
            "添加成功"
        )
        
        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
    }
}

/**
 * 文件列表表格模型
 */
class FileListTableModel : AbstractTableModel() {
    private data class FileItem(val file: File, var remoteName: String)
    
    private val items = mutableListOf<FileItem>()
    private val columns = arrayOf("本地文件", "远程文件名", "大小")

    override fun getRowCount() = items.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 1

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = items[rowIndex]
        return when (columnIndex) {
            0 -> item.file.name
            1 -> item.remoteName
            2 -> "${item.file.length() / 1024} KB"
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 1 && aValue is String && aValue.isNotBlank()) {
            items[rowIndex].remoteName = aValue
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    fun addFile(file: File) {
        if (items.none { it.file.path == file.path }) {
            items.add(FileItem(file, file.name))
            fireTableRowsInserted(items.size - 1, items.size - 1)
        }
    }

    fun removeFile(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            fireTableRowsDeleted(index, index)
        }
    }

    fun getFiles(): List<Pair<File, String>> = items.map { it.file to it.remoteName }
}
