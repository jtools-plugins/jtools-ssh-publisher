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
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
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
import javax.swing.table.AbstractTableModel

/**
 * 多文件上传对话框 - 批量上传的增强版
 * 每个文件可以独立配置：目标服务器、远程路径、脚本选择
 */
class MultiFileUploadDialog(
    private val project: Project
) : DialogWrapper(project, true), Disposable {

    // 文件配置列表
    private val fileConfigs = mutableListOf<FileUploadConfig>()
    private lateinit var fileListModel: DefaultListModel<FileUploadConfig>
    private lateinit var fileList: JBList<FileUploadConfig>
    
    // 当前选中文件的配置面板
    private val configCardLayout = CardLayout()
    private val configCardPanel = JPanel(configCardLayout)
    private val emptyPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("请先在左侧添加文件", SwingConstants.CENTER), BorderLayout.CENTER)
    }
    
    // 所有服务器列表（用于每个文件的服务器选择）
    private val allServers = mutableListOf<SshConfig>()
    
    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    init {
        title = "批量上传"
        setSize(950, 750)
        setOKButtonText("添加到上传队列")
        setCancelButtonText("关闭")
        loadServers()
        init()
    }

    private fun loadServers() {
        allServers.addAll(SshConfigService.getConfigs())
    }

    override fun createCenterPanel(): JComponent {
        // 文件列表
        fileListModel = DefaultListModel()
        fileList = JBList(fileListModel).apply {
            cellRenderer = FileListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }
        
        // 文件列表面板（带工具栏）
        val fileListPanel = ToolbarDecorator.createDecorator(fileList)
            .setAddAction { addFiles() }
            .setRemoveAction { removeSelectedFile() }
            .disableUpDownActions()
            .setPreferredSize(Dimension(180, 400))
            .createPanel()
        
        // 文件列表选择监听
        fileList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedIndex = fileList.selectedIndex
                if (selectedIndex >= 0 && selectedIndex < fileConfigs.size) {
                    configCardLayout.show(configCardPanel, "file_$selectedIndex")
                } else {
                    configCardLayout.show(configCardPanel, "empty")
                }
            }
        }
        
        // 初始化配置卡片面板
        configCardPanel.add(emptyPanel, "empty")
        configCardLayout.show(configCardPanel, "empty")
        
        // 左侧：文件列表
        val leftPanel = JPanel(BorderLayout(0, 5)).apply {
            border = JBUI.Borders.empty(5)
            add(JBLabel("上传文件列表:"), BorderLayout.NORTH)
            add(fileListPanel, BorderLayout.CENTER)
        }
        
        // 右侧：文件配置
        val rightPanel = JPanel(BorderLayout(0, 5)).apply {
            border = JBUI.Borders.empty(5)
            add(JBLabel("文件配置（选择左侧文件查看）:"), BorderLayout.NORTH)
            add(configCardPanel, BorderLayout.CENTER)
        }
        
        // 左右分割
        val mainSplit = JBSplitter(false).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
            proportion = 0.2f
            dividerWidth = 5
        }
        
        // 提示信息
        val tipLabel = JBLabel("<html><font color='gray'>提示：每个文件可独立配置目标服务器、远程路径和脚本，相当于同时创建多条批量上传任务</font></html>")

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(mainSplit, BorderLayout.CENTER)
            add(tipLabel, BorderLayout.SOUTH)
        }
    }

    /**
     * 添加文件
     */
    private fun addFiles() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
        descriptor.title = "选择要上传的文件"
        
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            files.forEach { vf ->
                val file = File(vf.path)
                if (file.exists() && file.isFile) {
                    val config = FileUploadConfig(
                        localFile = file,
                        remotePath = "/tmp",
                        remoteFileName = file.name,
                        selectedServerIds = mutableSetOf(),
                        serverScriptSelections = mutableMapOf()
                    )
                    
                    // 初始化每个服务器的脚本选择
                    allServers.forEach { server ->
                        val preScripts = SshConfigService.getPreScripts(server.id)
                        val postScripts = SshConfigService.getPostScripts(server.id)
                        config.serverScriptSelections[server.id] = ServerScriptConfig(
                            preScripts = preScripts.map { ScriptSelection(it, false) }.toMutableList(),
                            postScripts = postScripts.map { ScriptSelection(it, false) }.toMutableList()
                        )
                    }
                    
                    fileConfigs.add(config)
                    fileListModel.addElement(config)
                    
                    // 创建该文件的配置面板
                    val index = fileConfigs.size - 1
                    configCardPanel.add(createFileConfigPanel(config, index), "file_$index")
                }
            }
            
            // 选中最后添加的文件
            if (fileListModel.size() > 0) {
                fileList.selectedIndex = fileListModel.size() - 1
            }
        }
    }
    
    /**
     * 移除选中的文件
     */
    private fun removeSelectedFile() {
        val selectedIndex = fileList.selectedIndex
        if (selectedIndex >= 0) {
            fileConfigs.removeAt(selectedIndex)
            fileListModel.remove(selectedIndex)
            
            // 重建所有配置面板
            rebuildConfigPanels()
            
            if (fileListModel.size() > 0) {
                fileList.selectedIndex = minOf(selectedIndex, fileListModel.size() - 1)
            } else {
                configCardLayout.show(configCardPanel, "empty")
            }
        }
    }
    
    /**
     * 重建所有文件配置面板
     */
    private fun rebuildConfigPanels() {
        val components = configCardPanel.components.toList()
        components.forEach { comp ->
            if (comp != emptyPanel) {
                configCardPanel.remove(comp)
            }
        }
        
        fileConfigs.forEachIndexed { index, config ->
            configCardPanel.add(createFileConfigPanel(config, index), "file_$index")
        }
    }

    
    /**
     * 创建单个文件的配置面板（类似BatchUploadDialog的布局）
     */
    private fun createFileConfigPanel(config: FileUploadConfig, index: Int): JComponent {
        val remotePathField = JBTextField(config.remotePath)
        val remoteFileNameField = JBTextField(config.remoteFileName)
        
        // 服务器选择列表
        val serverCheckList = CheckBoxList<SshConfig>()
        allServers.forEach { server ->
            serverCheckList.addItem(server, "${server.name} (${server.host})", config.selectedServerIds.contains(server.id))
        }
        
        // 脚本配置卡片
        val scriptCardLayout = CardLayout()
        val scriptCardPanel = JPanel(scriptCardLayout)
        val emptyScriptPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("点击左侧服务器配置脚本", SwingConstants.CENTER), BorderLayout.CENTER)
        }
        scriptCardPanel.add(emptyScriptPanel, "empty")
        
        // 为每个服务器创建脚本配置面板
        allServers.forEach { server ->
            val scriptConfig = config.serverScriptSelections[server.id]
            if (scriptConfig != null) {
                scriptCardPanel.add(createServerScriptPanel(scriptConfig), "server_${server.id}")
            }
        }
        scriptCardLayout.show(scriptCardPanel, "empty")
        
        // 临时脚本编辑器
        val tempPreScriptEditor = MultiLanguageTextField(shellFileType, project, config.tempPreScript, isLineNumbersShown = true)
        val tempPostScriptEditor = MultiLanguageTextField(shellFileType, project, config.tempPostScript, isLineNumbersShown = true)
        Disposer.register(disposable, tempPreScriptEditor)
        Disposer.register(disposable, tempPostScriptEditor)
        
        // 监听变化
        remotePathField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = update()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = update()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = update()
            private fun update() { config.remotePath = remotePathField.text }
        })
        
        remoteFileNameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = update()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = update()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = update()
            private fun update() { 
                config.remoteFileName = remoteFileNameField.text
                fileListModel.setElementAt(config, index)
            }
        })
        
        tempPreScriptEditor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                config.tempPreScript = tempPreScriptEditor.text
            }
        })
        
        tempPostScriptEditor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                config.tempPostScript = tempPostScriptEditor.text
            }
        })
        
        // 服务器选择变化监听
        serverCheckList.setCheckBoxListListener { idx, selected ->
            val server = serverCheckList.getItemAt(idx)
            if (server != null) {
                if (selected) {
                    config.selectedServerIds.add(server.id)
                } else {
                    config.selectedServerIds.remove(server.id)
                }
            }
        }
        
        // 服务器列表选择监听（显示对应的脚本配置）
        serverCheckList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedIdx = serverCheckList.selectedIndex
                if (selectedIdx >= 0) {
                    val server = serverCheckList.getItemAt(selectedIdx)
                    if (server != null) {
                        scriptCardLayout.show(scriptCardPanel, "server_${server.id}")
                    }
                }
            }
        }
        
        // 文件信息面板
        val fileInfoPanel = JPanel(java.awt.GridBagLayout()).apply {
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(3)
            }
            
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            add(JBLabel("本地文件:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(JBLabel(config.localFile.name).apply {
                toolTipText = config.localFile.absolutePath
            }, gbc)
            
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
            add(JBLabel("远程目录:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(remotePathField, gbc)
            
            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
            add(JBLabel("远程文件名:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(remoteFileNameField, gbc)
        }
        
        // 服务器选择面板
        val serverPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("选择服务器:"), BorderLayout.WEST)
                add(JPanel().apply {
                    add(JButton("全选").apply { 
                        addActionListener { 
                            for (i in 0 until serverCheckList.itemsCount) {
                                val server = serverCheckList.getItemAt(i)
                                serverCheckList.setItemSelected(server, true)
                                if (server != null) config.selectedServerIds.add(server.id)
                            }
                            serverCheckList.repaint()
                        } 
                    })
                    add(JButton("取消").apply { 
                        addActionListener { 
                            for (i in 0 until serverCheckList.itemsCount) {
                                serverCheckList.setItemSelected(serverCheckList.getItemAt(i), false)
                            }
                            config.selectedServerIds.clear()
                            serverCheckList.repaint()
                        } 
                    })
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(serverCheckList), BorderLayout.CENTER)
        }
        
        // 脚本配置面板
        val scriptPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("服务器脚本配置（点击服务器查看）:"), BorderLayout.NORTH)
            add(scriptCardPanel, BorderLayout.CENTER)
        }
        
        // 服务器和脚本水平分割
        val serverScriptSplit = JBSplitter(false).apply {
            firstComponent = serverPanel
            secondComponent = scriptPanel
            proportion = 0.4f
            dividerWidth = 5
        }
        
        // 临时脚本Tab
        val tempScriptTabs = JBTabbedPane().apply {
            addTab("临时前置脚本", JPanel(BorderLayout(0, 3)).apply {
                add(JBLabel("上传前执行:"), BorderLayout.NORTH)
                add(tempPreScriptEditor.apply { preferredSize = Dimension(400, 100) }, BorderLayout.CENTER)
            })
            addTab("临时后置脚本", JPanel(BorderLayout(0, 3)).apply {
                add(JBLabel("上传后执行:"), BorderLayout.NORTH)
                add(tempPostScriptEditor.apply { preferredSize = Dimension(400, 100) }, BorderLayout.CENTER)
            })
            minimumSize = Dimension(100, 100)
        }
        
        // 上下分割：服务器选择 / 临时脚本
        val mainSplit = JBSplitter(true).apply {
            firstComponent = serverScriptSplit
            secondComponent = tempScriptTabs
            proportion = 0.55f
            dividerWidth = 5
        }
        
        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(5)
            add(fileInfoPanel, BorderLayout.NORTH)
            add(mainSplit, BorderLayout.CENTER)
        }
    }
    
    /**
     * 创建服务器脚本配置面板
     */
    private fun createServerScriptPanel(scriptConfig: ServerScriptConfig): JComponent {
        val preModel = ScriptSelectionTableModel(scriptConfig.preScripts)
        val postModel = ScriptSelectionTableModel(scriptConfig.postScripts)

        val preTable = createScriptTable(preModel)
        val postTable = createScriptTable(postModel)

        return JBTabbedPane().apply {
            addTab("前置脚本 (${scriptConfig.preScripts.size})", JBScrollPane(preTable))
            addTab("后置脚本 (${scriptConfig.postScripts.size})", JBScrollPane(postTable))
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

    override fun doOKAction() {
        if (fileConfigs.isEmpty()) {
            Messages.showErrorDialog(project, "请添加要上传的文件", "错误")
            return
        }

        // 检查每个文件是否至少选择了一个服务器
        val filesWithoutServer = fileConfigs.filter { it.selectedServerIds.isEmpty() }
        if (filesWithoutServer.isNotEmpty()) {
            Messages.showErrorDialog(
                project, 
                "以下文件未选择目标服务器:\n${filesWithoutServer.joinToString("\n") { "- ${it.localFile.name}" }}", 
                "错误"
            )
            return
        }

        // 为每个文件和每个选中的服务器创建上传任务
        val tasks = mutableListOf<TransferTask>()
        
        fileConfigs.forEach { fileConfig ->
            val fullRemotePath = if (fileConfig.remotePath.endsWith("/")) {
                fileConfig.remotePath + fileConfig.remoteFileName
            } else {
                "${fileConfig.remotePath}/${fileConfig.remoteFileName}"
            }
            
            fileConfig.selectedServerIds.forEach { serverId ->
                val server = allServers.find { it.id == serverId } ?: return@forEach
                val scriptConfig = fileConfig.serverScriptSelections[serverId]
                
                val preScripts = scriptConfig?.preScripts
                    ?.filter { it.selected }
                    ?.map { it.script }
                    ?: emptyList()
                val postScripts = scriptConfig?.postScripts
                    ?.filter { it.selected }
                    ?.map { it.script }
                    ?: emptyList()
                
                tasks.add(TransferTask(
                    type = TransferTask.TransferType.UPLOAD,
                    localFile = fileConfig.localFile,
                    remotePath = fullRemotePath,
                    config = server,
                    fileSize = fileConfig.localFile.length(),
                    preScripts = preScripts,
                    postScripts = postScripts,
                    tempPreScript = fileConfig.tempPreScript,
                    tempPostScript = fileConfig.tempPostScript
                ))
            }
        }

        // 添加到任务管理器
        TransferTaskManager.addTasks(tasks)

        val serverCount = fileConfigs.sumOf { it.selectedServerIds.size }
        Messages.showInfoMessage(
            project,
            "已添加 ${tasks.size} 个上传任务到队列\n(${fileConfigs.size} 个文件，共 $serverCount 个服务器目标)\n可在\"传输管理\"面板查看进度",
            "提示"
        )

        super.doOKAction()
    }

    override fun dispose() {
        super.dispose()
    }
    
    /**
     * 文件列表渲染器
     */
    private class FileListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is FileUploadConfig) {
                text = value.remoteFileName
                icon = AllIcons.FileTypes.Any_type
                toolTipText = "${value.localFile.absolutePath} -> ${value.selectedServerIds.size} 台服务器"
            }
            return this
        }
    }
}

/**
 * 文件上传配置
 */
data class FileUploadConfig(
    val localFile: File,
    var remotePath: String = "/tmp",
    var remoteFileName: String = localFile.name,
    val selectedServerIds: MutableSet<String> = mutableSetOf(),
    val serverScriptSelections: MutableMap<String, ServerScriptConfig> = mutableMapOf(),
    var tempPreScript: String = "",
    var tempPostScript: String = ""
)

/**
 * 服务器脚本配置
 */
data class ServerScriptConfig(
    val preScripts: MutableList<ScriptSelection>,
    val postScripts: MutableList<ScriptSelection>
)

/**
 * 脚本选择项
 */
data class ScriptSelection(val script: ScriptConfig, var selected: Boolean)

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
