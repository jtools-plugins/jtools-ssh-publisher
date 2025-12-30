package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.ConfigExportImportService
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.TransferTaskManager
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class MainView(
    private val parentDisposable: Disposable,
    val project: Project
) : SimpleToolWindowPanel(true), Disposable {

    private lateinit var tree: Tree
    private lateinit var treeModel: DefaultTreeModel
    private val rootNode = DefaultMutableTreeNode()

    private val terminalTabs = DockableTabPanel(parentDisposable)
    private val leftTabs = com.intellij.ui.components.JBTabbedPane(JTabbedPane.TOP)
    private val splitPane = JBSplitter(true, 0.4f)
    private lateinit var transferTaskPanel: TransferTaskPanel
    private lateinit var uploadTemplatePanel: UploadTemplatePanel

    init {
        Disposer.register(parentDisposable, this)
        initTree()
        initTransferTaskPanel()
        initUploadTemplatePanel()
        initActionToolbar()
        initSplitPane()
        refreshTree()
    }

    private fun initTransferTaskPanel() {
        transferTaskPanel = TransferTaskPanel()
        Disposer.register(parentDisposable, transferTaskPanel)
    }

    private fun initUploadTemplatePanel() {
        uploadTemplatePanel = UploadTemplatePanel(project)
    }

    private fun initSplitPane() {
        // 左侧Tab：SSH连接 + 上传模板 + 传输管理
        leftTabs.addTab("SSH连接", AllIcons.Nodes.Plugin, JBScrollPane(tree))
        leftTabs.addTab("上传模板", AllIcons.Actions.Upload, uploadTemplatePanel)
        leftTabs.addTab("传输管理", AllIcons.Toolwindows.ToolWindowMessages, transferTaskPanel)
        
        splitPane.firstComponent = leftTabs
        splitPane.secondComponent = terminalTabs
        setContent(splitPane)
    }

    private fun initTree() {
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
        }
        TreeSpeedSearch(tree)

        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode ?: return
                when (val userObject = node.userObject) {
                    is SshConfig -> {
                        icon = AllIcons.Nodes.Plugin
                        append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  ${userObject.host}:${userObject.port}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
                    getSelectedConfig()?.let { openTerminal(it) }
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
                        is SshConfig -> showConfigContextMenu(e, node.userObject as SshConfig)
                        is String -> showGroupContextMenu(e, node.userObject as String)
                    }
                }
            }
        })
    }

    private fun getSelectedConfig(): SshConfig? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return node?.userObject as? SshConfig
    }

    private fun showConfigContextMenu(e: MouseEvent, config: SshConfig) {
        JPopupMenu().apply {
            add(createMenuItem("打开终端", AllIcons.Debugger.Console) { openTerminal(config) })
            add(createMenuItem("文件系统", AllIcons.Nodes.Folder) { openFileSystem(config) })
            add(createMenuItem("上传文件", AllIcons.Actions.Upload) { openUploadDialog(config) })
            addSeparator()
            add(createMenuItem("编辑", AllIcons.Actions.Edit) { editConfig(config) })
            add(createMenuItem("复制", AllIcons.Actions.Copy) { copyConfig(config) })
            add(createMenuItem("删除", AllIcons.Actions.GC) { deleteConfig(config) })
        }.show(e.component, e.x, e.y)
    }

    private fun showGroupContextMenu(e: MouseEvent, group: String) {
        JPopupMenu().apply {
            add(createMenuItem("新建配置到此分组", AllIcons.Actions.AddFile) {
                AddItemDialog(project, null, group) { refreshTree() }.show()
            })
        }.show(e.component, e.x, e.y)
    }

    private fun createMenuItem(text: String, icon: Icon, action: () -> Unit): JMenuItem {
        return JMenuItem(text, icon).apply { addActionListener { action() } }
    }

    private fun openTerminal(config: SshConfig) {
        val terminalPanel = SshTerminalPanel(parentDisposable, config, project)
        addTab(config.name, AllIcons.Debugger.Console, terminalPanel, config.host)
    }

    private fun openFileSystem(config: SshConfig) {
        val fileSystemPanel = SftpFileSystemPanel(config, project)
        Disposer.register(parentDisposable, fileSystemPanel)
        addTab("${config.name} [文件]", AllIcons.Nodes.Folder, fileSystemPanel, config.host)
    }

    private fun addTab(title: String, icon: Icon, component: JComponent, tooltip: String) {
        terminalTabs.addTab(title, icon, component, tooltip)
    }

    private fun openUploadDialog(config: SshConfig) {
        UploadDialog(project, config).show()
    }

    private fun editConfig(config: SshConfig) {
        AddItemDialog(project, config, config.group) { refreshTree() }.show()
    }

    private fun copyConfig(config: SshConfig) {
        val newConfig = config.copy(
            id = System.currentTimeMillis().toString(),
            name = "${config.name} (副本)"
        )
        SshConfigService.addConfig(newConfig)
        refreshTree()
    }

    private fun deleteConfig(config: SshConfig) {
        val result = Messages.showYesNoDialog(
            project,
            "确定要删除 \"${config.name}\" 吗？",
            "确认删除",
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            SshConfigService.removeConfig(config.id)
            refreshTree()
        }
    }

    fun refreshTree() {
        // 保存当前展开的组
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

        val configsByGroup = SshConfigService.getConfigsByGroup()
        configsByGroup.toSortedMap().forEach { (group, configs) ->
            val groupNode = DefaultMutableTreeNode(group)
            configs.sortedBy { it.name }.forEach { config ->
                groupNode.add(DefaultMutableTreeNode(config))
            }
            rootNode.add(groupNode)
        }

        treeModel.reload()
        
        // 恢复之前展开的组
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
    private fun expandAllNodes() {
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
    private fun collapseAllNodes() {
        for (i in 0 until rootNode.childCount) {
            val groupNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode
            if (groupNode != null) {
                val path = javax.swing.tree.TreePath(arrayOf(rootNode, groupNode))
                tree.collapsePath(path)
            }
        }
    }

    private fun initActionToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction({ "新建配置" }, AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> AddItemDialog(project, null, null) { refreshTree() }.show()
                        1 -> uploadTemplatePanel.showNewTemplateDialog()
                    }
                }
                override fun update(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> e.presentation.text = "新建配置"
                        1 -> e.presentation.text = "新建模板"
                        else -> e.presentation.isVisible = false
                    }
                    e.presentation.isVisible = leftTabs.selectedIndex in 0..1
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "批量上传" }, AllIcons.Actions.Upload) {
                override fun actionPerformed(e: AnActionEvent) {
                    MultiFileUploadDialog(project).show()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = leftTabs.selectedIndex == 0
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "刷新" }, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> refreshTree()
                        1 -> uploadTemplatePanel.refreshTree()
                        2 -> transferTaskPanel.refreshList()
                    }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            addSeparator()

            add(object : AnAction({ "全部展开" }, AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> expandAllNodes()
                        1 -> uploadTemplatePanel.expandAllNodes()
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = leftTabs.selectedIndex in 0..1
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "全部折叠" }, AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> collapseAllNodes()
                        1 -> uploadTemplatePanel.collapseAllNodes()
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = leftTabs.selectedIndex in 0..1
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            
            addSeparator()
            
            add(object : AnAction({ "导出配置" }, AllIcons.ToolbarDecorator.Export) {
                override fun actionPerformed(e: AnActionEvent) {
                    exportConfigs()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            
            add(object : AnAction({ "导入配置" }, AllIcons.ToolbarDecorator.Import) {
                override fun actionPerformed(e: AnActionEvent) {
                    importConfigs()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ssh-publisher-toolbar", actionGroup, true)
        toolbar.targetComponent = this

        this.toolbar = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    override fun dispose() {
        Disposer.dispose(terminalTabs)
        TransferTaskManager.shutdown()
    }
    
    /**
     * 导出配置
     */
    private fun exportConfigs() {
        // 询问导出方式
        val choice = Messages.showDialog(
            project,
            "请选择导出方式:",
            "导出配置",
            arrayOf("导出全部", "选择导出", "取消"),
            0,
            Messages.getQuestionIcon()
        )
        
        when (choice) {
            0 -> exportAllConfigs()
            1 -> exportSelectedConfigs()
        }
    }
    
    /**
     * 导出全部配置
     */
    private fun exportAllConfigs() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "选择导出目录"
        
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            val fileName = "ssh-configs-${System.currentTimeMillis()}.json"
            val file = File(vf.path, fileName)
            
            ConfigExportImportService.exportToFile(file).fold(
                onSuccess = { count ->
                    Messages.showInfoMessage(
                        project,
                        "成功导出 $count 个配置到:\n${file.absolutePath}",
                        "导出成功"
                    )
                },
                onFailure = { e ->
                    Messages.showErrorDialog(
                        project,
                        "导出失败: ${e.message}",
                        "错误"
                    )
                }
            )
        }
    }
    
    /**
     * 选择导出配置
     */
    private fun exportSelectedConfigs() {
        val dialog = ExportSelectDialog(project)
        if (dialog.showAndGet()) {
            val selectedIds = dialog.getSelectedConfigIds()
            val selectedTemplateIds = dialog.getSelectedTemplateIds()
            if (selectedIds.isEmpty() && selectedTemplateIds.isEmpty()) {
                Messages.showWarningDialog(project, "未选择任何配置", "提示")
                return
            }
            
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            descriptor.title = "选择导出目录"
            
            FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
                val fileName = "ssh-configs-${System.currentTimeMillis()}.json"
                val file = File(vf.path, fileName)
                
                ConfigExportImportService.exportSelectedToFile(file, selectedIds, selectedTemplateIds).fold(
                    onSuccess = { count ->
                        Messages.showInfoMessage(
                            project,
                            "成功导出 $count 个配置到:\n${file.absolutePath}",
                            "导出成功"
                        )
                    },
                    onFailure = { e ->
                        Messages.showErrorDialog(
                            project,
                            "导出失败: ${e.message}",
                            "错误"
                        )
                    }
                )
            }
        }
    }
    
    /**
     * 导入配置
     */
    private fun importConfigs() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        descriptor.title = "选择配置文件"
        
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            val file = File(vf.path)
            
            // 询问是否覆盖
            val overwrite = Messages.showYesNoCancelDialog(
                project,
                "如果存在同名配置（相同分组+名称），是否覆盖？\n\n是 - 覆盖现有配置\n否 - 跳过已存在的配置",
                "导入配置",
                "覆盖",
                "跳过",
                "取消",
                Messages.getQuestionIcon()
            )
            
            if (overwrite == Messages.CANCEL) return@let
            
            ConfigExportImportService.importFromFile(file, overwrite == Messages.YES).fold(
                onSuccess = { result ->
                    refreshTree()
                    Messages.showInfoMessage(
                        project,
                        "导入完成:\n- 新增: ${result.imported} 个\n- 更新: ${result.updated} 个\n- 跳过: ${result.skipped} 个",
                        "导入成功"
                    )
                },
                onFailure = { e ->
                    Messages.showErrorDialog(
                        project,
                        "导入失败: ${e.message}",
                        "错误"
                    )
                }
            )
        }
    }
}
