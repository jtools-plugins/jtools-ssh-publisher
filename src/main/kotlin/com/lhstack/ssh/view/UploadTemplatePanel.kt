package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.TransferTask
import com.lhstack.ssh.model.UploadTemplate
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.TransferTaskManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * 上传任务模板面板
 */
class UploadTemplatePanel(private val project: Project) : JPanel(BorderLayout()) {

    private lateinit var tree: Tree
    private lateinit var treeModel: DefaultTreeModel
    private val rootNode = DefaultMutableTreeNode()

    init {
        initToolbar()
        initTree()
        refreshTree()
    }
    
    /**
     * 显示新建模板对话框
     */
    fun showNewTemplateDialog() {
        UploadTemplateDialog(project, null) { refreshTree() }.show()
    }

    private fun initToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction({ "新建模板" }, AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) {
                    UploadTemplateDialog(project, null) { refreshTree() }.show()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction({ "刷新" }, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshTree()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            addSeparator()
            add(object : AnAction({ "执行选中" }, AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) {
                    executeSelectedTemplates()
                }
                override fun update(e: AnActionEvent) {
                    val selectedTemplates = getSelectedTemplates()
                    e.presentation.isEnabled = selectedTemplates.isNotEmpty()
                    e.presentation.text = if (selectedTemplates.size > 1) "执行选中 (${selectedTemplates.size})" else "执行选中"
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("upload-template-toolbar", actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
    }

    private fun initTree() {
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            // 启用多选
            selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        }

        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                when (val userObject = node.userObject) {
                    is UploadTemplate -> {
                        icon = AllIcons.Actions.Upload
                        append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val sshConfig = SshConfigService.getConfigById(userObject.sshConfigId)
                        val serverName = sshConfig?.name ?: "未知服务器"
                        append("  → $serverName", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is String -> {
                        icon = AllIcons.Nodes.Folder
                        append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  (${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    getSelectedTemplate()?.let { executeTemplate(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)

            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    tree.selectionPath = path
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return

                    when (node.userObject) {
                        is UploadTemplate -> showTemplateContextMenu(e, node.userObject as UploadTemplate)
                        is String -> showGroupContextMenu(e, node.userObject as String)
                    }
                }
            }
        })

        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    private fun getSelectedTemplate(): UploadTemplate? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return node?.userObject as? UploadTemplate
    }
    
    /**
     * 获取所有选中的模板（支持多选）
     */
    private fun getSelectedTemplates(): List<UploadTemplate> {
        val templates = mutableListOf<UploadTemplate>()
        tree.selectionPaths?.forEach { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            when (val userObject = node?.userObject) {
                is UploadTemplate -> templates.add(userObject)
                is String -> {
                    // 如果选中的是分组，添加该分组下所有模板
                    for (i in 0 until (node.childCount)) {
                        val childNode = node.getChildAt(i) as? DefaultMutableTreeNode
                        (childNode?.userObject as? UploadTemplate)?.let { templates.add(it) }
                    }
                }
            }
        }
        return templates.distinctBy { it.id }
    }
    
    /**
     * 批量执行选中的模板
     */
    private fun executeSelectedTemplates() {
        val templates = getSelectedTemplates()
        if (templates.isEmpty()) {
            Messages.showWarningDialog(project, "请选择要执行的模板", "提示")
            return
        }
        
        var successCount = 0
        val errors = mutableListOf<String>()
        
        templates.forEach { template ->
            val file = File(template.localPath)
            if (!file.exists()) {
                errors.add("${template.name}: 本地文件不存在")
                return@forEach
            }
            
            val sshConfig = SshConfigService.getConfigById(template.sshConfigId)
            if (sshConfig == null) {
                errors.add("${template.name}: 目标服务器配置不存在")
                return@forEach
            }
            
            // 获取选中的脚本
            val preScripts = template.preScriptIds.mapNotNull { id ->
                SshConfigService.getScriptsByConfigId(template.sshConfigId).find { it.id == id }
            }
            val postScripts = template.postScriptIds.mapNotNull { id ->
                SshConfigService.getScriptsByConfigId(template.sshConfigId).find { it.id == id }
            }
            
            val remoteFileName = template.remoteFileName.ifEmpty { file.name }
            val task = TransferTask(
                type = TransferTask.TransferType.UPLOAD,
                localFile = file,
                remotePath = "${template.remotePath}/$remoteFileName",
                config = sshConfig,
                fileSize = file.length(),
                preScripts = preScripts,
                postScripts = postScripts,
                tempPreScript = template.preScript,
                tempPostScript = template.postScript
            )
            
            TransferTaskManager.addTask(task)
            successCount++
        }
        
        if (errors.isEmpty()) {
            Messages.showInfoMessage(project, "已添加 $successCount 个任务到传输队列", "执行成功")
        } else {
            val errorMsg = errors.joinToString("\n")
            Messages.showWarningDialog(
                project,
                "成功添加 $successCount 个任务\n\n以下模板执行失败:\n$errorMsg",
                "部分执行失败"
            )
        }
    }

    private fun showTemplateContextMenu(e: MouseEvent, template: UploadTemplate) {
        val selectedTemplates = getSelectedTemplates()
        JPopupMenu().apply {
            if (selectedTemplates.size > 1) {
                add(createMenuItem("批量执行 (${selectedTemplates.size})", AllIcons.Actions.Execute) { 
                    executeSelectedTemplates() 
                })
                addSeparator()
            } else {
                add(createMenuItem("执行", AllIcons.Actions.Execute) { executeTemplate(template) })
                addSeparator()
            }
            add(createMenuItem("编辑", AllIcons.Actions.Edit) { editTemplate(template) })
            add(createMenuItem("复制", AllIcons.Actions.Copy) { copyTemplate(template) })
            add(createMenuItem("删除", AllIcons.Actions.GC) { deleteTemplate(template) })
        }.show(e.component, e.x, e.y)
    }

    private fun showGroupContextMenu(e: MouseEvent, group: String) {
        JPopupMenu().apply {
            add(createMenuItem("新建模板到此分组", AllIcons.Actions.AddFile) {
                UploadTemplateDialog(project, null, group) { refreshTree() }.show()
            })
        }.show(e.component, e.x, e.y)
    }

    private fun createMenuItem(text: String, icon: Icon, action: () -> Unit): JMenuItem {
        return JMenuItem(text, icon).apply { addActionListener { action() } }
    }

    private fun executeTemplate(template: UploadTemplate) {
        val file = File(template.localPath)
        if (!file.exists()) {
            Messages.showErrorDialog(project, "本地文件不存在: ${template.localPath}", "执行失败")
            return
        }

        val sshConfig = SshConfigService.getConfigById(template.sshConfigId)
        if (sshConfig == null) {
            Messages.showErrorDialog(project, "目标服务器配置不存在", "执行失败")
            return
        }

        // 获取选中的脚本
        val preScripts = template.preScriptIds.mapNotNull { id ->
            SshConfigService.getScriptsByConfigId(template.sshConfigId).find { it.id == id }
        }
        val postScripts = template.postScriptIds.mapNotNull { id ->
            SshConfigService.getScriptsByConfigId(template.sshConfigId).find { it.id == id }
        }

        val remoteFileName = template.remoteFileName.ifEmpty { file.name }
        val task = TransferTask(
            type = TransferTask.TransferType.UPLOAD,
            localFile = file,
            remotePath = "${template.remotePath}/$remoteFileName",
            config = sshConfig,
            fileSize = file.length(),
            preScripts = preScripts,
            postScripts = postScripts,
            tempPreScript = template.preScript,
            tempPostScript = template.postScript
        )

        TransferTaskManager.addTask(task)
        Messages.showInfoMessage(project, "已添加到传输队列", "执行成功")
    }

    private fun editTemplate(template: UploadTemplate) {
        UploadTemplateDialog(project, template) { refreshTree() }.show()
    }

    private fun copyTemplate(template: UploadTemplate) {
        val newTemplate = template.copy(
            id = System.currentTimeMillis().toString(),
            name = "${template.name} (副本)",
            createTime = System.currentTimeMillis()
        )
        SshConfigService.addUploadTemplate(newTemplate)
        refreshTree()
    }

    private fun deleteTemplate(template: UploadTemplate) {
        val result = Messages.showYesNoDialog(
            project,
            "确定要删除 \"${template.name}\" 吗？",
            "确认删除",
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            SshConfigService.removeUploadTemplate(template.id)
            refreshTree()
        }
    }

    fun refreshTree() {
        val expandedGroups = mutableSetOf<String>()
        for (i in 0 until rootNode.childCount) {
            val groupNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode
            if (groupNode != null) {
                val path = javax.swing.tree.TreePath(arrayOf(rootNode, groupNode))
                if (tree.isExpanded(path)) {
                    expandedGroups.add(groupNode.userObject as String)
                }
            }
        }

        rootNode.removeAllChildren()

        val templatesByGroup = SshConfigService.getUploadTemplatesByGroup()
        templatesByGroup.toSortedMap().forEach { (group, templates) ->
            val groupNode = DefaultMutableTreeNode(group)
            templates.sortedBy { it.name }.forEach { template ->
                groupNode.add(DefaultMutableTreeNode(template))
            }
            rootNode.add(groupNode)
        }

        treeModel.reload()

        for (i in 0 until rootNode.childCount) {
            val groupNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode
            if (groupNode != null && expandedGroups.contains(groupNode.userObject as String)) {
                val path = javax.swing.tree.TreePath(arrayOf(rootNode, groupNode))
                tree.expandPath(path)
            }
        }
    }
    
    /**
     * 展开所有节点
     */
    fun expandAllNodes() {
        for (i in 0 until rootNode.childCount) {
            val groupNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode
            if (groupNode != null) {
                val path = javax.swing.tree.TreePath(arrayOf(rootNode, groupNode))
                tree.expandPath(path)
            }
        }
    }
    
    /**
     * 收起所有节点
     */
    fun collapseAllNodes() {
        for (i in 0 until rootNode.childCount) {
            val groupNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode
            if (groupNode != null) {
                val path = javax.swing.tree.TreePath(arrayOf(rootNode, groupNode))
                tree.collapsePath(path)
            }
        }
    }
}


/**
 * 上传模板编辑对话框
 */
class UploadTemplateDialog(
    private val project: Project,
    private val template: UploadTemplate?,
    private val defaultGroup: String? = null,
    private val onSaved: () -> Unit = {}
) : DialogWrapper(project, true), Disposable {

    private val groupField = JBTextField(template?.group ?: defaultGroup ?: "")
    private val nameField = JBTextField(template?.name ?: "")
    private val localPathField = JBTextField(template?.localPath ?: "")
    private val serverCombo = ComboBox<SshConfig>()
    private val remotePathField = JBTextField(template?.remotePath ?: "/tmp")
    private val remoteFileNameField = JBTextField(template?.remoteFileName ?: "")
    
    // 脚本选择表格
    private val preScriptsModel = TemplateScriptTableModel()
    private val postScriptsModel = TemplateScriptTableModel()
    private val preScriptsTable = JBTable(preScriptsModel)
    private val postScriptsTable = JBTable(postScriptsModel)
    
    // 临时脚本编辑器
    private lateinit var tempPreScriptEditor: MultiLanguageTextField
    private lateinit var tempPostScriptEditor: MultiLanguageTextField
    
    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    init {
        title = if (template == null) "新建上传模板" else "编辑上传模板"
        setSize(700, 700)
        init()
        loadServers()
    }

    private fun loadServers() {
        val configs = SshConfigService.getConfigs()
        configs.forEach { serverCombo.addItem(it) }
        
        serverCombo.renderer = ListCellRenderer { _, value, _, _, _ ->
            JLabel(if (value != null) "${value.name} (${value.host})" else "").apply {
                border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
            }
        }

        // 服务器切换时更新脚本列表
        serverCombo.addActionListener {
            updateScriptsList()
        }

        if (template != null) {
            configs.find { it.id == template.sshConfigId }?.let {
                serverCombo.selectedItem = it
            }
        } else if (configs.isNotEmpty()) {
            serverCombo.selectedIndex = 0
        }
        
        updateScriptsList()
    }
    
    private fun updateScriptsList() {
        val selectedConfig = serverCombo.selectedItem as? SshConfig ?: return
        
        preScriptsModel.clear()
        postScriptsModel.clear()
        
        // 编辑模式下恢复之前选中的脚本
        val selectedPreIds = template?.preScriptIds ?: emptyList()
        val selectedPostIds = template?.postScriptIds ?: emptyList()
        
        SshConfigService.getPreScripts(selectedConfig.id).forEach {
            preScriptsModel.addScript(it, it.id in selectedPreIds)
        }
        SshConfigService.getPostScripts(selectedConfig.id).forEach {
            postScriptsModel.addScript(it, it.id in selectedPostIds)
        }
    }

    override fun createCenterPanel(): JComponent {
        // 创建Shell编辑器
        tempPreScriptEditor = MultiLanguageTextField(shellFileType, project, template?.preScript ?: "", isLineNumbersShown = true)
        tempPostScriptEditor = MultiLanguageTextField(shellFileType, project, template?.postScript ?: "", isLineNumbersShown = true)
        Disposer.register(disposable, tempPreScriptEditor)
        Disposer.register(disposable, tempPostScriptEditor)
        
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        // 分组
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("分组:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(groupField, gbc)

        // 名称
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("名称:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(nameField, gbc)

        // 本地文件
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("本地文件:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val localPathPanel = JPanel(BorderLayout(4, 0))
        localPathPanel.add(localPathField, BorderLayout.CENTER)
        val browseBtn = JButton("浏览").apply {
            addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
                    localPathField.text = vf.path
                    // 自动填充远程文件名
                    if (remoteFileNameField.text.isEmpty()) {
                        remoteFileNameField.text = vf.name
                    }
                }
            }
        }
        localPathPanel.add(browseBtn, BorderLayout.EAST)
        panel.add(localPathPanel, gbc)

        // 目标服务器
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("目标服务器:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(serverCombo, gbc)

        // 远程路径
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("远程路径:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(remotePathField, gbc)

        // 远程文件名
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("远程文件名:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        remoteFileNameField.toolTipText = "留空则使用原文件名"
        panel.add(remoteFileNameField, gbc)

        // 脚本Tab
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        gbc.weightx = 1.0; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        val scriptTabs = JTabbedPane().apply {
            addTab("前置脚本", createScriptPanel(preScriptsTable, tempPreScriptEditor, "上传前执行"))
            addTab("后置脚本", createScriptPanel(postScriptsTable, tempPostScriptEditor, "上传后执行"))
        }
        panel.add(scriptTabs, gbc)

        panel.preferredSize = Dimension(700, 550)
        return panel
    }
    
    private fun createScriptPanel(table: JBTable, editor: MultiLanguageTextField, label: String): JComponent {
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
                preferredSize = Dimension(650, 100)
            }

        val editorPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("临时脚本 ($label):"), BorderLayout.NORTH)
            add(editor.apply { preferredSize = Dimension(650, 120) }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(5)
            add(JPanel(BorderLayout()).apply {
                add(JBLabel("服务器脚本 (可选):"), BorderLayout.NORTH)
                add(tablePanel, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editorPanel, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        if (nameField.text.isBlank()) {
            Messages.showErrorDialog(project, "请输入模板名称", "验证失败")
            return
        }
        if (localPathField.text.isBlank()) {
            Messages.showErrorDialog(project, "请选择本地文件", "验证失败")
            return
        }
        if (serverCombo.selectedItem == null) {
            Messages.showErrorDialog(project, "请选择目标服务器", "验证失败")
            return
        }

        val selectedServer = serverCombo.selectedItem as SshConfig
        val newTemplate = UploadTemplate(
            id = template?.id ?: System.currentTimeMillis().toString(),
            group = groupField.text.trim(),
            name = nameField.text.trim(),
            localPath = localPathField.text.trim(),
            sshConfigId = selectedServer.id,
            remotePath = remotePathField.text.trim().ifEmpty { "/tmp" },
            remoteFileName = remoteFileNameField.text.trim(),
            preScript = tempPreScriptEditor.text,
            postScript = tempPostScriptEditor.text,
            preScriptIds = preScriptsModel.getSelectedScriptIds(),
            postScriptIds = postScriptsModel.getSelectedScriptIds(),
            createTime = template?.createTime ?: System.currentTimeMillis()
        )

        if (template == null) {
            SshConfigService.addUploadTemplate(newTemplate)
        } else {
            SshConfigService.updateUploadTemplate(newTemplate)
        }

        super.doOKAction()
        onSaved()
    }
    
    override fun dispose() {
        super.dispose()
    }
}

/**
 * 模板脚本选择表格模型
 */
class TemplateScriptTableModel : AbstractTableModel() {
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
    
    fun clear() {
        val size = items.size
        items.clear()
        if (size > 0) {
            fireTableRowsDeleted(0, size - 1)
        }
    }

    fun getSelectedScriptIds(): List<String> = items.filter { it.selected }.map { it.script.id }
    
    fun getSelectedScripts(): List<ScriptConfig> = items.filter { it.selected }.map { it.script }
}
