package com.lhstack.ssh.view

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
import com.lhstack.ssh.PluginIcons
import com.lhstack.ssh.component.CollapsibleSection
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.component.SCRIPT_EDITOR_HEIGHT
import com.lhstack.ssh.component.verticalScrollPane
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.TransferTask
import com.lhstack.ssh.service.LocalShellDetector
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
    private lateinit var tempLocalPreScriptEditor: MultiLanguageTextField
    private lateinit var tempLocalPostScriptEditor: MultiLanguageTextField
    private lateinit var tempLocalPreShellCombo: JComboBox<ScriptConfig.ShellType>
    private lateinit var tempLocalPostShellCombo: JComboBox<ScriptConfig.ShellType>

    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    /** 当前 OS 可用 Shell 列表，延迟初始化一次 */
    private val availableShells: List<ScriptConfig.ShellType> by lazy {
        LocalShellDetector.availableShells()
    }

    init {
        title = "上传文件 - ${config.name} (${config.host})"
        setSize(680, 760)
        setOKButtonText("添加任务")
        setCancelButtonText("关闭")
        loadScripts()
        init()
    }

    /** 前置/后置表格各自同时装载远程脚本和本地脚本，执行位置由表格「位置」列区分。 */
    private fun loadScripts() {
        (SshConfigService.getPreScripts(config.id) + SshConfigService.getLocalPreScripts(config.id))
            .forEach { preScriptsModel.addScript(it, false) }
        (SshConfigService.getPostScripts(config.id) + SshConfigService.getLocalPostScripts(config.id))
            .forEach { postScriptsModel.addScript(it, false) }
    }

    override fun createCenterPanel(): JComponent {
        tempPreScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        tempPostScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        tempLocalPreScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        tempLocalPostScriptEditor = MultiLanguageTextField(shellFileType, project, "", isLineNumbersShown = true)
        Disposer.register(disposable, tempPreScriptEditor)
        Disposer.register(disposable, tempPostScriptEditor)
        Disposer.register(disposable, tempLocalPreScriptEditor)
        Disposer.register(disposable, tempLocalPostScriptEditor)

        tempLocalPreShellCombo  = buildShellCombo(availableShells)
        tempLocalPostShellCombo = buildShellCombo(availableShells)

        val filePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("本地文件:"), JPanel(BorderLayout(5, 0)).apply {
                add(localFileField, BorderLayout.CENTER)
                add(JButton("浏览", PluginIcons.Open).apply {
                    addActionListener {
                        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
                            localFileField.text = vf.path
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

        val scriptTabs = JBTabbedPane().apply {
            addTab("前置脚本", createScriptPanel(
                preScriptsTable, tempPreScriptEditor,
                tempLocalPreScriptEditor, tempLocalPreShellCombo, "上传前执行"
            ))
            addTab("后置脚本", createScriptPanel(
                postScriptsTable, tempPostScriptEditor,
                tempLocalPostScriptEditor, tempLocalPostShellCombo, "上传后执行"
            ))
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(filePanel, BorderLayout.NORTH)
            add(scriptTabs, BorderLayout.CENTER)
        }
    }

    /**
     * 单个 Tab 内容：三个可折叠区块（服务器脚本 / 远程临时脚本 / 本地临时脚本），
     * 整体套纵向滚动条，编辑器高度固定，弹窗高度不受影响。
     */
    private fun createScriptPanel(
        table: JBTable,
        remoteEditor: MultiLanguageTextField,
        localEditor: MultiLanguageTextField,
        shellCombo: JComboBox<ScriptConfig.ShellType>,
        label: String
    ): JComponent {
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        table.rowHeight = 24
        table.columnModel.getColumn(0).apply { preferredWidth = 40; maxWidth = 40; minWidth = 40 }
        table.columnModel.getColumn(1).apply { preferredWidth = 150; minWidth = 100 }
        table.columnModel.getColumn(2).apply { preferredWidth = 60; maxWidth = 60; minWidth = 60 }
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        val tablePanel = ToolbarDecorator.createDecorator(table)
            .disableAddAction().disableRemoveAction()
            .createPanel().apply { preferredSize = Dimension(600, 120) }

        remoteEditor.preferredSize = Dimension(600, SCRIPT_EDITOR_HEIGHT)
        localEditor.preferredSize = Dimension(600, SCRIPT_EDITOR_HEIGHT)

        return verticalScrollPane(
            CollapsibleSection("已保存脚本（可选）", tablePanel),
            CollapsibleSection("临时脚本（$label，远程执行，不保存）", remoteEditor),
            CollapsibleSection("本地临时脚本（$label，本机执行，不保存）", localEditor, trailing = shellCombo)
        )
    }

    override fun doOKAction() {
        val localPath  = localFileField.text.trim()
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

        val remoteFileName = remoteFileNameField.text.trim().ifEmpty { localFile.name }
        val fullRemotePath = if (remotePath.endsWith("/")) remotePath + remoteFileName
                            else "$remotePath/$remoteFileName"

        val task = TransferTask(
            type            = TransferTask.TransferType.UPLOAD,
            localFile       = localFile,
            remotePath      = fullRemotePath,
            config          = config,
            fileSize        = localFile.length(),
            preScripts      = preScriptsModel.getSelectedScripts(),
            postScripts     = postScriptsModel.getSelectedScripts(),
            tempPreScript   = tempPreScriptEditor.text.trim(),
            tempPostScript  = tempPostScriptEditor.text.trim(),
            tempLocalPreScript    = tempLocalPreScriptEditor.text.trim(),
            tempLocalPreShellType = tempLocalPreShellCombo.selectedItem as? ScriptConfig.ShellType
                ?: ScriptConfig.ShellType.DEFAULT,
            tempLocalPostScript    = tempLocalPostScriptEditor.text.trim(),
            tempLocalPostShellType = tempLocalPostShellCombo.selectedItem as? ScriptConfig.ShellType
                ?: ScriptConfig.ShellType.DEFAULT,
            localWorkDir = project.basePath
        )

        TransferTaskManager.addTask(task)
        Messages.showInfoMessage(project, "已添加上传任务: ${localFile.name}", "提示")
        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
    }
}

// ---------------------------------------------------------------------------
// 工具函数：构建 Shell 下拉框
// ---------------------------------------------------------------------------

/** 根据当前 OS 检测可用 Shell，创建带 label 渲染的 JComboBox。 */
fun buildShellCombo(shells: List<ScriptConfig.ShellType>): JComboBox<ScriptConfig.ShellType> {
    val combo = JComboBox<ScriptConfig.ShellType>()
    shells.forEach { combo.addItem(it) }
    combo.renderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = (value as? ScriptConfig.ShellType)?.label ?: ""
            return this
        }
    }
    return combo
}

// ---------------------------------------------------------------------------

/**
 * 上传脚本表格模型
 */
class UploadScriptTableModel : AbstractTableModel() {
    private data class ScriptItem(val script: ScriptConfig, var selected: Boolean)

    private val items = mutableListOf<ScriptItem>()
    private val columns = arrayOf("选择", "名称", "位置", "内容预览")

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
            2 -> if (item.script.scriptType.isLocal) "本机" else "服务器"
            3 -> item.script.content.replace("\n", " ").take(60)
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
