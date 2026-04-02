package com.lhstack.ssh.view

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
import com.lhstack.ssh.PluginIcons
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.ConfigExportImportService
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.TransferTaskManager
import java.awt.BorderLayout
import java.awt.Dimension
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

    companion object {
        private const val DEFAULT_TOP_PANEL_PROPORTION = 0.4f
        private const val COLLAPSED_TOP_PANEL_PROPORTION = 0.02f
        private const val DEFAULT_DIVIDER_WIDTH = 7
    }

    private lateinit var tree: Tree
    private lateinit var treeModel: DefaultTreeModel
    private val rootNode = DefaultMutableTreeNode()

    private val terminalTabs = DockableTabPanel(parentDisposable)
    private val leftTabs = com.intellij.ui.components.JBTabbedPane(JTabbedPane.TOP)
    private val splitPane = JBSplitter(true, DEFAULT_TOP_PANEL_PROPORTION)
    private val topPanelCollapseState = MainViewCollapseState(
        defaultExpandedProportion = DEFAULT_TOP_PANEL_PROPORTION,
        collapsedProportion = COLLAPSED_TOP_PANEL_PROPORTION
    )
    private lateinit var topPanelContainer: JPanel
    private lateinit var mainToolbar: ActionToolbar
    private lateinit var topPanelToggleToolbar: ActionToolbar
    private lateinit var transferTaskPanel: TransferTaskPanel
    private lateinit var uploadTemplatePanel: UploadTemplatePanel
    private val toggleTopPanelAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            toggleTopPanelCollapsed()
        }

        override fun update(e: AnActionEvent) {
            val collapsed = topPanelCollapseState.collapsed
            e.presentation.icon = if (collapsed) PluginIcons.ExpandAll else PluginIcons.CollapseAll
            e.presentation.text = if (collapsed) "展开面板" else "收起面板"
            e.presentation.description = if (collapsed) {
                "展开 SSH连接 / 上传模板 / 传输管理 面板"
            } else {
                "收起 SSH连接 / 上传模板 / 传输管理 面板"
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

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
        leftTabs.addTab("SSH连接", PluginIcons.SshConnection, JBScrollPane(tree))
        leftTabs.addTab("上传模板", PluginIcons.UploadTemplate, uploadTemplatePanel)
        leftTabs.addTab("传输管理", PluginIcons.TransferManager, transferTaskPanel)

        initTopPanelContainer()

        splitPane.firstComponent = topPanelContainer
        splitPane.secondComponent = terminalTabs
        setContent(splitPane)
        applyTopPanelState(DEFAULT_TOP_PANEL_PROPORTION)
    }

    private fun initTopPanelContainer() {
        topPanelContainer = JPanel(BorderLayout()).apply {
            add(leftTabs, BorderLayout.CENTER)
            minimumSize = Dimension(0, 0)
        }
    }

    private fun toggleTopPanelCollapsed() {
        val targetProportion = if (topPanelCollapseState.collapsed) {
            topPanelCollapseState.expand()
        } else {
            topPanelCollapseState.collapse(splitPane.proportion)
        }
        applyTopPanelState(targetProportion)
    }

    private fun applyTopPanelState(targetProportion: Float) {
        topPanelContainer.isVisible = topPanelCollapseState.topPanelVisible
        topPanelContainer.minimumSize = Dimension(0, 0)
        splitPane.dividerWidth = if (topPanelCollapseState.collapsed) 0 else DEFAULT_DIVIDER_WIDTH
        mainToolbar.updateActionsImmediately()
        topPanelToggleToolbar.updateActionsImmediately()

        topPanelContainer.revalidate()
        topPanelContainer.repaint()
        splitPane.revalidate()
        splitPane.repaint()

        SwingUtilities.invokeLater {
            splitPane.proportion = targetProportion
            splitPane.revalidate()
            splitPane.repaint()
        }
    }

    private fun areTopPanelControlsVisible(): Boolean = topPanelCollapseState.topPanelControlsVisible

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
                        icon = PluginIcons.SshConnection
                        append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  ${userObject.host}:${userObject.port}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is String -> {
                        icon = PluginIcons.Folder
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
            add(createMenuItem("打开终端", PluginIcons.Terminal) { openTerminal(config) })
            add(createMenuItem("文件系统", PluginIcons.Folder) { openFileSystem(config) })
            add(createMenuItem("上传文件", PluginIcons.Upload) { openUploadDialog(config) })
            addSeparator()
            add(createMenuItem("编辑", PluginIcons.Edit) { editConfig(config) })
            add(createMenuItem("复制", PluginIcons.Copy) { copyConfig(config) })
            add(createMenuItem("删除", PluginIcons.Delete) { deleteConfig(config) })
        }.show(e.component, e.x, e.y)
    }

    private fun showGroupContextMenu(e: MouseEvent, group: String) {
        JPopupMenu().apply {
            add(createMenuItem("新建配置到此分组", PluginIcons.Add) {
                AddItemDialog(project, null, group) { refreshTree() }.show()
            })
        }.show(e.component, e.x, e.y)
    }

    private fun createMenuItem(text: String, icon: Icon, action: () -> Unit): JMenuItem {
        return JMenuItem(text, icon).apply { addActionListener { action() } }
    }

    private fun openTerminal(config: SshConfig) {
        val terminalPanel = SshTerminalPanel(parentDisposable, config, project)
        addTab(config.name, PluginIcons.Terminal, terminalPanel, config.host)
    }

    private fun openFileSystem(config: SshConfig) {
        val fileSystemPanel = SftpFileSystemPanel(config, project)
        Disposer.register(parentDisposable, fileSystemPanel)
        addTab("${config.name} [文件]", PluginIcons.Folder, fileSystemPanel, config.host)
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
            "确定要删除 SSH 配置 \"${config.name}\" 吗？删除后将无法继续使用该连接配置，关联脚本也会一并删除。",
            "确认删除",
            Messages.getWarningIcon()
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
            add(object : AnAction({ "新建配置" }, PluginIcons.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> AddItemDialog(project, null, null) { refreshTree() }.show()
                        1 -> uploadTemplatePanel.showNewTemplateDialog()
                    }
                }
                override fun update(e: AnActionEvent) {
                    if (!areTopPanelControlsVisible()) {
                        e.presentation.isVisible = false
                        return
                    }
                    when (leftTabs.selectedIndex) {
                        0 -> e.presentation.text = "新建配置"
                        1 -> e.presentation.text = "新建模板"
                        else -> e.presentation.isVisible = false
                    }
                    e.presentation.isVisible = leftTabs.selectedIndex in 0..1
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "批量上传" }, PluginIcons.BatchUpload) {
                override fun actionPerformed(e: AnActionEvent) {
                    MultiFileUploadDialog(project).show()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = areTopPanelControlsVisible() && leftTabs.selectedIndex == 0
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "刷新" }, PluginIcons.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> refreshTree()
                        1 -> uploadTemplatePanel.refreshTree()
                        2 -> transferTaskPanel.refreshList()
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = areTopPanelControlsVisible()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            addSeparator()

            add(object : AnAction({ "全部展开" }, PluginIcons.ExpandAll) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> expandAllNodes()
                        1 -> uploadTemplatePanel.expandAllNodes()
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = areTopPanelControlsVisible() && leftTabs.selectedIndex in 0..1
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "全部折叠" }, PluginIcons.CollapseAll) {
                override fun actionPerformed(e: AnActionEvent) {
                    when (leftTabs.selectedIndex) {
                        0 -> collapseAllNodes()
                        1 -> uploadTemplatePanel.collapseAllNodes()
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = areTopPanelControlsVisible() && leftTabs.selectedIndex in 0..1
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            
            addSeparator()
            
            add(object : AnAction({ "导出配置" }, PluginIcons.Export) {
                override fun actionPerformed(e: AnActionEvent) {
                    exportConfigs()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = areTopPanelControlsVisible()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            
            add(object : AnAction({ "导入配置" }, PluginIcons.Import) {
                override fun actionPerformed(e: AnActionEvent) {
                    importConfigs()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = areTopPanelControlsVisible()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        mainToolbar = ActionManager.getInstance()
            .createActionToolbar("ssh-publisher-toolbar", actionGroup, true)
        mainToolbar.targetComponent = this

        topPanelToggleToolbar = ActionManager.getInstance()
            .createActionToolbar(
                "ssh-publisher-top-panel-toggle",
                DefaultActionGroup(toggleTopPanelAction),
                true
            ).apply {
                targetComponent = this@MainView
            }

        this.toolbar = JPanel(BorderLayout()).apply {
            add(mainToolbar.component, BorderLayout.WEST)
            add(topPanelToggleToolbar.component, BorderLayout.EAST)
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
            if (overwrite == Messages.YES) {
                val confirmed = Messages.showYesNoDialog(
                    project,
                    "将覆盖同名配置，并可能更新关联脚本与上传模板引用。\n建议先导出备份后再继续。\n\n是否仍要执行覆盖导入？",
                    "确认覆盖导入",
                    Messages.getWarningIcon()
                )
                if (confirmed != Messages.YES) {
                    return@let
                }
            }
            
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
