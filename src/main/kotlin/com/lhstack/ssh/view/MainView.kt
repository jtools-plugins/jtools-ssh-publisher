package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.SshConfigService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    private val terminalTabs = com.intellij.ui.components.JBTabbedPane(JTabbedPane.TOP)
    private val splitPane = JBSplitter(true, 0.4f)

    init {
        Disposer.register(parentDisposable, this)
        initTree()
        initActionToolbar()
        initSplitPane()
        refreshTree()
    }

    private fun initSplitPane() {
        splitPane.firstComponent = JBScrollPane(tree)
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
                if (e.clickCount == 2) {
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
        val terminalPanel = SshTerminalPanel(parentDisposable, config)
        val tabIndex = terminalTabs.tabCount
        terminalTabs.insertTab(config.name, AllIcons.Debugger.Console, terminalPanel, config.host, tabIndex)
        terminalTabs.selectedIndex = tabIndex

        // 创建自定义Tab组件
        val tabPanel = JPanel(BorderLayout(5, 0)).apply {
            isOpaque = false
            
            // 左侧：图标和标题
            add(JPanel(BorderLayout(3, 0)).apply {
                isOpaque = false
                add(JLabel(AllIcons.Debugger.Console), BorderLayout.WEST)
                add(JLabel(config.name), BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            
            // 右侧：关闭按钮
            val closeBtn = JLabel(AllIcons.Actions.Close)
            closeBtn.toolTipText = "关闭"
            closeBtn.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val idx = terminalTabs.indexOfComponent(terminalPanel)
                    if (idx >= 0) {
                        Disposer.dispose(terminalPanel)
                        terminalTabs.removeTabAt(idx)
                    }
                }
                override fun mouseEntered(e: MouseEvent) {
                    closeBtn.icon = AllIcons.Actions.CloseHovered
                }
                override fun mouseExited(e: MouseEvent) {
                    closeBtn.icon = AllIcons.Actions.Close
                }
            })
            add(closeBtn, BorderLayout.EAST)
        }
        
        terminalTabs.setTabComponentAt(tabIndex, tabPanel)
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

        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    private fun initActionToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction({ "新建配置" }, AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) {
                    AddItemDialog(project, null, null) { refreshTree() }.show()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "刷新" }, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshTree()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            addSeparator()

            add(object : AnAction({ "全部展开" }, AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) {
                    for (i in 0 until tree.rowCount) tree.expandRow(i)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            add(object : AnAction({ "全部折叠" }, AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) {
                    for (i in tree.rowCount - 1 downTo 0) tree.collapseRow(i)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("jtools-ssh-publisher-toolbar", actionGroup, true)
        toolbar.targetComponent = this

        this.toolbar = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    override fun dispose() {
        for (i in 0 until terminalTabs.tabCount) {
            val component = terminalTabs.getComponentAt(i)
            if (component is Disposable) {
                Disposer.dispose(component)
            }
        }
    }
}
