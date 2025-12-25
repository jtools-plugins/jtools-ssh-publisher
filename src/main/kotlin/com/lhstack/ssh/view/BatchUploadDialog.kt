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
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBSplitter
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
import com.lhstack.ssh.model.TransferTask
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.TransferTaskManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel

/**
 * 批量上传对话框 - 同一文件上传到多台服务器，支持为每个服务器选择脚本
 */
class BatchUploadDialog(
    private val project: Project
) : DialogWrapper(project, true), Disposable {

    private val localFileField = JBTextField()
    private val remotePathField = JBTextField("/tmp")
    private val remoteFileNameField = JBTextField()
    private val serverCheckList = CheckBoxList<SshConfig>()
    
    // 每个服务器的脚本选择状态: configId -> (preScripts, postScripts)
    private val serverScriptSelections = mutableMapOf<String, ServerScriptSelection>()
    
    // 当前选中服务器的脚本面板
    private val scriptCardLayout = CardLayout()
    private val scriptCardPanel = JPanel(scriptCardLayout)
    private val emptyScriptPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("请先在左侧选择一个服务器来配置其脚本", SwingConstants.CENTER), BorderLayout.CENTER)
    }

    private lateinit var tempPreScriptEditor: MultiLanguageTextField
    private lateinit var tempPostScriptEditor: MultiLanguageTextField

    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    init {
        title = "批量上传文件"
        setSize(800, 650)
        setOKButtonText("添加到上传队列")
        setCancelButtonText("关闭")
        loadServers()
        init()
    }

    private fun loadServers() {
        val configs = SshConfigService.getConfigs()
        configs.forEach { config ->
            serverCheckList.addItem(config, "${config.name} (${config.host})", false)
            // 初始化每个服务器的脚本选择
            val preScripts = SshConfigService.getPreScripts(config.id)
            val postScripts = SshConfigService.getPostScripts(config.id)
            serverScriptSelections[config.id] = ServerScriptSelection(
                config = config,
                preScripts = preScripts.map { ScriptSelection(it, it.enabled) }.toMutableList(),
                postScripts = postScripts.map { ScriptSelection(it, it.enabled) }.toMutableList()
            )
        }
    }

    override fun createCenterPanel(): JComponent {
        // 创建临时脚本编辑器
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
                            if (remoteFileNameField.text.isEmpty()) {
                                remoteFileNameField.text = vf.name
                            }
                        }
                    }
                }, BorderLayout.EAST)
            })
            .addLabeledComponent(JBLabel("远程目录:"), remotePathField)
            .addLabeledComponent(JBLabel("远程文件名:"), remoteFileNameField.apply {
                toolTipText = "留空则使用本地文件名"
            })
            .panel

        // 服务器选择面板（左侧）
        val serverListPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("选择服务器:"), BorderLayout.WEST)
                add(JPanel().apply {
                    add(JButton("全选").apply { addActionListener { selectAll(true) } })
                    add(JButton("取消").apply { addActionListener { selectAll(false) } })
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(serverCheckList), BorderLayout.CENTER)
        }

        // 初始化脚本卡片面板
        scriptCardPanel.add(emptyScriptPanel, "empty")
        serverScriptSelections.forEach { (configId, selection) ->
            scriptCardPanel.add(createServerScriptPanel(selection), "server_$configId")
        }
        scriptCardLayout.show(scriptCardPanel, "empty")

        // 服务器列表选择监听
        serverCheckList.addListSelectionListener(ListSelectionListener {
            val selectedIndex = serverCheckList.selectedIndex
            if (selectedIndex >= 0) {
                val config = serverCheckList.getItemAt(selectedIndex)
                if (config != null) {
                    scriptCardLayout.show(scriptCardPanel, "server_${config.id}")
                }
            }
        })

        // 脚本选择面板（右侧）
        val scriptSelectionPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("服务器脚本配置（点击左侧服务器查看）:"), BorderLayout.NORTH)
            add(scriptCardPanel, BorderLayout.CENTER)
        }

        // 服务器和脚本选择区域（使用JSplitPane）
        val serverScriptSplit = JBSplitter(false).apply {
            this.firstComponent = serverListPanel
            this.secondComponent = scriptSelectionPanel
            proportion = 0.43f
            dividerWidth = 5
        }

        // 临时脚本Tab（使用JSplitPane可调整高度）
        val scriptTabs = JBTabbedPane().apply {
            addTab("临时前置脚本", createTempScriptPanel(tempPreScriptEditor, "上传前在每台服务器执行"))
            addTab("临时后置脚本", createTempScriptPanel(tempPostScriptEditor, "上传后在每台服务器执行"))
            minimumSize = Dimension(100, 100)
        }

        // 上下分割：服务器选择 / 临时脚本
        val mainSplit = JBSplitter(true).apply {
            this.firstComponent = serverScriptSplit
            this.secondComponent = scriptTabs
            proportion = 0.43f
            dividerWidth = 5
        }

        // 提示信息
        val tipLabel = JBLabel("<html><font color='gray'>提示：勾选服务器后，点击服务器可配置其前置/后置脚本；拖动分隔条可调整区域大小</font></html>")

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(filePanel, BorderLayout.NORTH)
            add(mainSplit, BorderLayout.CENTER)
            add(tipLabel, BorderLayout.SOUTH)
        }
    }

    private fun createServerScriptPanel(selection: ServerScriptSelection): JComponent {
        val preModel = ScriptSelectionTableModel(selection.preScripts)
        val postModel = ScriptSelectionTableModel(selection.postScripts)

        val preTable = createScriptTable(preModel)
        val postTable = createScriptTable(postModel)

        return JBTabbedPane().apply {
            addTab("前置脚本 (${selection.preScripts.size})", JBScrollPane(preTable))
            addTab("后置脚本 (${selection.postScripts.size})", JBScrollPane(postTable))
        }
    }

    private fun createScriptTable(model: ScriptSelectionTableModel): JBTable {
        return JBTable(model).apply {
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
            rowHeight = 24
            columnModel.getColumn(0).apply {
                preferredWidth = 40
                maxWidth = 40
                minWidth = 40
            }
            columnModel.getColumn(1).preferredWidth = 120
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        }
    }

    private fun createTempScriptPanel(editor: MultiLanguageTextField, hint: String): JComponent {
        return JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel(hint), BorderLayout.NORTH)
            add(editor.apply { preferredSize = Dimension(730, 80) }, BorderLayout.CENTER)
        }
    }

    private fun selectAll(selected: Boolean) {
        for (i in 0 until serverCheckList.itemsCount) {
            serverCheckList.setItemSelected(serverCheckList.getItemAt(i), selected)
        }
        serverCheckList.repaint()
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

        val selectedServers = mutableListOf<SshConfig>()
        for (i in 0 until serverCheckList.itemsCount) {
            val item = serverCheckList.getItemAt(i)
            if (item != null && serverCheckList.isItemSelected(item)) {
                selectedServers.add(item)
            }
        }

        if (selectedServers.isEmpty()) {
            Messages.showErrorDialog(project, "请至少选择一台服务器", "错误")
            return
        }

        val remoteFileName = remoteFileNameField.text.trim().ifEmpty { localFile.name }
        val fullRemotePath = if (remotePath.endsWith("/")) {
            remotePath + remoteFileName
        } else {
            "$remotePath/$remoteFileName"
        }

        val tempPreScript = tempPreScriptEditor.text.trim()
        val tempPostScript = tempPostScriptEditor.text.trim()

        // 为每个服务器创建上传任务，使用用户选择的脚本
        val tasks = selectedServers.map { config ->
            val scriptSelection = serverScriptSelections[config.id]
            val preScripts = scriptSelection?.preScripts
                ?.filter { it.selected }
                ?.map { it.script }
                ?: emptyList()
            val postScripts = scriptSelection?.postScripts
                ?.filter { it.selected }
                ?.map { it.script }
                ?: emptyList()

            TransferTask(
                type = TransferTask.TransferType.UPLOAD,
                localFile = localFile,
                remotePath = fullRemotePath,
                config = config,
                fileSize = localFile.length(),
                preScripts = preScripts,
                postScripts = postScripts,
                tempPreScript = tempPreScript,
                tempPostScript = tempPostScript
            )
        }

        // 添加到任务管理器
        TransferTaskManager.addTasks(tasks)

        Messages.showInfoMessage(
            project,
            "已添加 ${tasks.size} 个上传任务到队列，可在\"传输管理\"面板查看进度",
            "提示"
        )

        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
    }
}

/**
 * 脚本选择项
 */
data class ScriptSelection(val script: ScriptConfig, var selected: Boolean)

/**
 * 服务器脚本选择状态
 */
data class ServerScriptSelection(
    val config: SshConfig,
    val preScripts: MutableList<ScriptSelection>,
    val postScripts: MutableList<ScriptSelection>
)

/**
 * 脚本选择表格模型
 */
class ScriptSelectionTableModel(private val scripts: MutableList<ScriptSelection>) : AbstractTableModel() {
    private val columns = arrayOf("选择", "名称", "内容预览")

    override fun getRowCount() = scripts.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = scripts[rowIndex]
        return when (columnIndex) {
            0 -> item.selected
            1 -> item.script.name
            2 -> item.script.content.replace("\n", " ").take(50)
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0 && aValue is Boolean) {
            scripts[rowIndex].selected = aValue
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }
}
