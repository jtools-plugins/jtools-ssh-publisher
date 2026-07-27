package com.lhstack.ssh.view

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.lhstack.ssh.PluginIcons
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.model.AnsibleGroupScript
import com.lhstack.ssh.model.AnsibleTask
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.SshGroup
import com.lhstack.ssh.service.AnsibleRunnerService
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.util.AnActionFactory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * 批量执行面板
 *
 * 布局：
 *   左侧：分组树（勾选服务器，右键菜单）
 *   右侧上：脚本区（JBSplitter：左=已保存脚本列表 右=实时编辑器）
 *   右侧下：执行结果（每台服务器一个 Tab）
 *
 * 执行顺序：已保存脚本可排序，实时脚本可选在保存脚本之前或之后执行。
 */
class AnsibleRunnerPanel(private val project: Project) : JPanel(BorderLayout()), AnsibleRunnerService.Listener {

    // ── 当前选中分组 ──
    private var selectedGroupId: String? = null

    // ── 左侧分组树 ──
    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = AnsibleTreeCellRenderer()
        selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
    }

    // ── 实时脚本编辑器 ──
    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType ?: PlainTextFileType.INSTANCE
    }
    private val scriptEditor = MultiLanguageTextField(shellFileType, project, "#!/bin/bash\n", isLineNumbersShown = true).also {
        Disposer.register(project, it)
    }

    // ── 已保存脚本列表（支持多选） ──
    private val savedScriptsList = JList<AnsibleGroupScript>().apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = ScriptCellRenderer()
    }

    // ── 实时脚本执行位置：before=在保存脚本之前，after=之后 ──
    private var liveScriptBefore = true

    // ── 结果区 ──
    private val resultTabs = JBTabbedPane()
    private val taskLogAreas = mutableMapOf<String, JBTextArea>()
    private var currentTasks = listOf<AnsibleTask>()
    private var running = false

    init {
        AnsibleRunnerService.addListener(this)
        buildUi()
        refreshTree()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    when (val obj = node.userObject) {
                        is CheckableConfig -> { obj.checked = !obj.checked; treeModel.nodeChanged(node) }
                        is CheckableGroup  -> { selectedGroupId = obj.group.id; refreshSavedScripts() }
                    }
                }
            }
            override fun mousePressed(e: MouseEvent)  = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
            private fun handlePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val obj = node.userObject as? CheckableGroup ?: return
                showGroupContextMenu(e, obj, node)
            }
        })

        SshTreeDragHandler(
            tree = tree,
            onDropped = { refreshTree() },
            configExtractor  = { (it as? CheckableConfig)?.config },
            groupIdExtractor = { if (it is CheckableGroup) it.group.id else null }
        )
    }

    // ─────────────────────────────────────────────────
    // UI 构建
    // ─────────────────────────────────────────────────

    private fun buildUi() {
        add(buildToolbar(), BorderLayout.NORTH)

        // 左侧：服务器树
        val leftPanel = JPanel(BorderLayout()).apply {
            minimumSize = Dimension(80, 0)
            add(JBLabel("  SSH 服务器").apply { border = BorderFactory.createEmptyBorder(4, 0, 4, 0) }, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(JButton("全选").apply { addActionListener { setCheckedInCurrentGroup(true) } })
                add(JButton("反选").apply { addActionListener { invertCheckedInCurrentGroup() } })
            }, BorderLayout.SOUTH)
        }

        // 右侧上：脚本区（已保存 | 实时编辑器）
        val scriptPanel = buildScriptPanel()

        // 右侧下：结果区
        val resultPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("  执行结果").apply { border = BorderFactory.createEmptyBorder(4, 0, 4, 0) }, BorderLayout.NORTH)
            add(JBScrollPane(resultTabs), BorderLayout.CENTER)
        }

        val rightSplitter = JBSplitter(true, 0.45f).apply {
            firstComponent  = scriptPanel
            secondComponent = resultPanel
            dividerWidth    = 5
        }
        val mainSplitter = JBSplitter(false, 0.22f).apply {
            firstComponent  = leftPanel
            secondComponent = rightSplitter
            dividerWidth    = 5
        }
        add(mainSplitter, BorderLayout.CENTER)
    }

    private fun buildToolbar(): JComponent {
        val stop    = AnActionFactory.create("停止",         PluginIcons.Stop)    { stopScripts() }
        val clear   = AnActionFactory.create("清空结果",     PluginIcons.Delete)  { clearResults() }
        val refresh = AnActionFactory.create("刷新服务器列表", PluginIcons.Refresh) { refreshTree() }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ansible-runner", DefaultActionGroup(stop, clear, refresh), true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    // ── 脚本分屏：左=已保存，右=实时编辑器 ──
    private fun buildScriptPanel(): JComponent {
        // 已保存脚本：列表 + 操作栏
        val savedPanel = buildSavedScriptsPanel()

        // 实时编辑器：header（位置切换 + 执行按钮）+ 编辑器
        val liveOrderLabel = JBLabel("实时脚本位置：")
        val beforeBtn = JRadioButton("在已保存之前", liveScriptBefore)
        val afterBtn  = JRadioButton("在已保存之后", !liveScriptBefore)
        val orderGroup = ButtonGroup().also { it.add(beforeBtn); it.add(afterBtn) }
        beforeBtn.addActionListener { liveScriptBefore = true }
        afterBtn.addActionListener  { liveScriptBefore = false }

        val liveHeader = JPanel(BorderLayout()).apply {
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(liveOrderLabel); add(beforeBtn); add(afterBtn)
            }
            add(left, BorderLayout.WEST)
            add(JButton("▶  执行").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                isFocusPainted = false
                addActionListener { runScripts() }
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(2, 4, 2, 6), border
                )
            }, BorderLayout.EAST)
        }
        val livePanel = JPanel(BorderLayout()).apply {
            add(liveHeader, BorderLayout.NORTH)
            add(scriptEditor, BorderLayout.CENTER)
        }

        return JBSplitter(false, 0.35f).apply {
            firstComponent  = savedPanel
            secondComponent = livePanel
            dividerWidth    = 5
        }
    }

    // ── 已保存脚本面板 ──
    private fun buildSavedScriptsPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 2)).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        }

        val header = JPanel(BorderLayout()).apply {
            add(JBLabel("  已保存脚本").apply {
                border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
            }, BorderLayout.WEST)
        }
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(savedScriptsList), BorderLayout.CENTER)

        // 操作栏：新建 编辑 删除 | ↑ ↓
        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("新建").apply { addActionListener { newScript() } })
            add(JButton("编辑").apply {
                addActionListener {
                    val sel = savedScriptsList.selectedValue ?: return@addActionListener
                    editScript(sel)
                }
            })
            add(JButton("删除").apply {
                addActionListener {
                    val selected = savedScriptsList.selectedValuesList
                    if (selected.isEmpty()) return@addActionListener
                    val msg = if (selected.size == 1) "确定删除脚本 \"${selected[0].name}\"？"
                              else "确定删除选中的 ${selected.size} 个脚本？"
                    if (Messages.showYesNoDialog(project, msg, "确认", Messages.getWarningIcon()) == Messages.YES) {
                        selected.forEach { SshConfigService.removeAnsibleScript(it.id) }
                        refreshSavedScripts()
                    }
                }
            })
            add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 20) })
            add(JButton("↑").apply {
                toolTipText = "上移"
                addActionListener { moveSavedScript(-1) }
            })
            add(JButton("↓").apply {
                toolTipText = "下移"
                addActionListener { moveSavedScript(1) }
            })
        }
        panel.add(btnPanel, BorderLayout.SOUTH)
        return panel
    }

    // ─────────────────────────────────────────────────
    // 树操作
    // ─────────────────────────────────────────────────

    fun refreshTree() {
        val expanded = mutableSetOf<String>()
        for (i in 0 until rootNode.childCount) {
            val n = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            if (tree.isExpanded(javax.swing.tree.TreePath(arrayOf(rootNode, n)))) {
                val obj = n.userObject
                if (obj is CheckableGroup) expanded.add(obj.group.id)
            }
        }
        val checked = mutableSetOf<String>()
        forEachLeafAll { n ->
            val obj = n.userObject
            if (obj is CheckableConfig && obj.checked) checked.add(obj.config.id)
        }

        rootNode.removeAllChildren()
        val groups = SshConfigService.getGroups()
        val configsByGroupId = SshConfigService.getConfigsByGroup()

        groups.sortedBy { it.name }.forEach { group ->
            val configs = configsByGroupId[group.id] ?: emptyList()
            if (configs.isEmpty()) return@forEach
            val groupNode = DefaultMutableTreeNode(CheckableGroup(group, true))
            configs.sortedWith(compareBy({ it.sortOrder }, { it.name })).forEach { config ->
                groupNode.add(DefaultMutableTreeNode(CheckableConfig(config, config.id in checked)))
            }
            rootNode.add(groupNode)
        }
        val ungrouped = configsByGroupId[""] ?: emptyList()
        if (ungrouped.isNotEmpty()) {
            val defaultNode = DefaultMutableTreeNode(CheckableGroup(SshGroup(id = "", name = "默认"), true))
            ungrouped.sortedWith(compareBy({ it.sortOrder }, { it.name })).forEach { config ->
                defaultNode.add(DefaultMutableTreeNode(CheckableConfig(config, config.id in checked)))
            }
            rootNode.add(defaultNode)
        }

        treeModel.reload()
        for (i in 0 until rootNode.childCount) {
            val n = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val obj = n.userObject
            if (obj is CheckableGroup && (obj.group.id in expanded || expanded.isEmpty())) {
                tree.expandPath(javax.swing.tree.TreePath(arrayOf(rootNode, n)))
            }
        }
        refreshSavedScripts()
    }

    private fun refreshSavedScripts() {
        val scripts = selectedGroupId?.let { SshConfigService.getAnsibleScriptsByGroup(it) } ?: emptyList()
        val model = DefaultListModel<AnsibleGroupScript>().also { m -> scripts.forEach { m.addElement(it) } }
        savedScriptsList.model = model
    }

    // ── 上移/下移已保存脚本 ──
    private fun moveSavedScript(delta: Int) {
        val idx = savedScriptsList.selectedIndex
        if (idx < 0) return
        val model = savedScriptsList.model as? DefaultListModel<AnsibleGroupScript> ?: return
        val newIdx = (idx + delta).coerceIn(0, model.size - 1)
        if (newIdx == idx) return

        // 重新对当前列表全量赋 sortOrder
        val list = (0 until model.size).map { model.getElementAt(it) }.toMutableList()
        val item = list.removeAt(idx)
        list.add(newIdx, item)
        list.forEachIndexed { i, script ->
            val updated = script.copy(sortOrder = i)
            SshConfigService.updateAnsibleScript(updated)
            model.set(i, updated)
        }
        savedScriptsList.selectedIndex = newIdx
    }

    // ── 分组右键菜单 ──
    private fun showGroupContextMenu(e: MouseEvent, group: CheckableGroup, groupNode: DefaultMutableTreeNode) {
        JBPopupFactory.getInstance().createActionGroupPopup(
            "分组操作",
            DefaultActionGroup().apply {
                add(AnActionFactory.create("全选此分组",     PluginIcons.SshConnection) { setGroupChecked(groupNode, true) })
                add(AnActionFactory.create("取消此分组全选", PluginIcons.Delete)        { setGroupChecked(groupNode, false) })
                addSeparator()
                add(AnActionFactory.create("新建脚本到此分组", PluginIcons.Add) { newScriptToGroup(group.group) })
            },
            DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
        ).show(RelativePoint(e.component, Point(e.x, e.y)))
    }

    private fun setGroupChecked(groupNode: DefaultMutableTreeNode, checked: Boolean) {
        for (i in 0 until groupNode.childCount) {
            val child = groupNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            (child.userObject as? CheckableConfig)?.checked = checked
        }
        treeModel.reload(groupNode)
    }

    private fun setCheckedInCurrentGroup(checked: Boolean) {
        val gid = selectedGroupId
        forEachLeafAll { node ->
            val obj = node.userObject
            if (obj is CheckableConfig && (gid == null || obj.config.groupId == gid)) obj.checked = checked
        }
        treeModel.reload()
    }

    private fun invertCheckedInCurrentGroup() {
        val gid = selectedGroupId
        forEachLeafAll { node ->
            val obj = node.userObject
            if (obj is CheckableConfig && (gid == null || obj.config.groupId == gid)) obj.checked = !obj.checked
        }
        treeModel.reload()
    }

    private fun forEachLeafAll(action: (DefaultMutableTreeNode) -> Unit) {
        fun walk(node: DefaultMutableTreeNode) {
            if (node.isLeaf) action(node)
            else for (i in 0 until node.childCount) walk(node.getChildAt(i) as DefaultMutableTreeNode)
        }
        walk(rootNode)
    }

    private fun getTargetConfigs(): List<SshConfig> {
        val result = mutableListOf<SshConfig>()
        forEachLeafAll { node ->
            val obj = node.userObject
            if (obj is CheckableConfig && obj.checked) {
                val gid = selectedGroupId
                if (gid == null || obj.config.groupId == gid) result.add(obj.config)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────────
    // 脚本管理
    // ─────────────────────────────────────────────────

    private fun newScript() {
        val groups = SshConfigService.getGroups()
        if (groups.isEmpty()) { Messages.showWarningDialog(project, "请先创建至少一个 SSH 连接分组", "提示"); return }
        val dialog = AnsibleScriptEditDialog(project, groups, defaultGroupId = selectedGroupId, isEdit = false)
        if (!dialog.showAndGet()) return
        val script = AnsibleGroupScript(
            groupId = dialog.getSelectedGroupId(),
            name    = dialog.getScriptName(),
            content = dialog.getScriptContent()
        )
        SshConfigService.addAnsibleScript(script)
        selectedGroupId = script.groupId
        refreshSavedScripts()
        val model = savedScriptsList.model as DefaultListModel<AnsibleGroupScript>
        for (i in 0 until model.size) {
            if (model.getElementAt(i).id == script.id) { savedScriptsList.selectedIndex = i; break }
        }
    }

    private fun newScriptToGroup(group: SshGroup) {
        val dialog = AnsibleScriptEditDialog(project, listOf(group), defaultGroupId = group.id, isEdit = false)
        if (!dialog.showAndGet()) return
        val script = AnsibleGroupScript(groupId = group.id, name = dialog.getScriptName(), content = dialog.getScriptContent())
        SshConfigService.addAnsibleScript(script)
        selectedGroupId = group.id
        refreshSavedScripts()
    }

    private fun editScript(script: AnsibleGroupScript) {
        val dialog = AnsibleScriptEditDialog(
            project, SshConfigService.getGroups(),
            defaultGroupId = script.groupId, initialName = script.name,
            initialContent = script.content, isEdit = true
        )
        if (!dialog.showAndGet()) return
        SshConfigService.updateAnsibleScript(script.copy(name = dialog.getScriptName(), content = dialog.getScriptContent()))
        refreshSavedScripts()
    }

    // ─────────────────────────────────────────────────
    // 执行
    // ─────────────────────────────────────────────────

    private fun runScripts() {
        val targets = getTargetConfigs()
        if (targets.isEmpty()) { Messages.showWarningDialog(project, "请先在左侧勾选至少一台服务器", "提示"); return }

        val savedContents = savedScriptsList.selectedValuesList.map { it.content }
        val liveContent   = scriptEditor.text.trim()

        // 按顺序拼接脚本
        val parts = mutableListOf<String>()
        if (liveContent.isNotEmpty() && liveScriptBefore) parts.add(liveContent)
        parts.addAll(savedContents)
        if (liveContent.isNotEmpty() && !liveScriptBefore) parts.add(liveContent)

        val combinedScript = parts.joinToString("\n\n")
        if (combinedScript.isBlank()) { Messages.showWarningDialog(project, "请在右侧选择或输入脚本内容", "提示"); return }

        running = true
        clearResults()
        currentTasks = targets.map { AnsibleTask(config = it, script = combinedScript) }
        currentTasks.forEach { task ->
            val area = JBTextArea().apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true
                font = font.deriveFont(12f)
            }
            taskLogAreas[task.id] = area
            resultTabs.addTab("${task.config.name} (${task.config.host})", JBScrollPane(area))
        }
        AnsibleRunnerService.runAll(currentTasks)
    }

    private fun stopScripts() { AnsibleRunnerService.stopAll(currentTasks); running = false }

    private fun clearResults() { resultTabs.removeAll(); taskLogAreas.clear(); currentTasks = emptyList() }

    // ─────────────────────────────────────────────────
    // AnsibleRunnerService.Listener
    // ─────────────────────────────────────────────────

    override fun onTaskUpdated(task: AnsibleTask) {
        val area = taskLogAreas[task.id] ?: return
        area.text = task.logs.joinToString("\n")
        area.caretPosition = area.document.length.coerceAtLeast(0)
        val tabIdx = currentTasks.indexOfFirst { it.id == task.id }
        if (tabIdx >= 0 && tabIdx < resultTabs.tabCount) {
            val icon = when (task.status) {
                AnsibleTask.Status.SUCCESS -> PluginIcons.Success
                AnsibleTask.Status.FAILED  -> PluginIcons.Error
                AnsibleTask.Status.RUNNING -> PluginIcons.Running
                AnsibleTask.Status.STOPPED -> PluginIcons.Stop
                else                       -> PluginIcons.Pending
            }
            resultTabs.setTitleAt(tabIdx, "${task.config.name} (${task.config.host})")
            resultTabs.setIconAt(tabIdx, icon)
        }
    }

    // ─────────────────────────────────────────────────
    // Data classes & Renderers
    // ─────────────────────────────────────────────────

    data class CheckableGroup(val group: SshGroup, var checked: Boolean)
    data class CheckableConfig(val config: SshConfig, var checked: Boolean)

    /** 已保存脚本列表 renderer：图标 + 粗体名称 */
    private class ScriptCellRenderer : ColoredListCellRenderer<AnsibleGroupScript>() {
        override fun customizeCellRenderer(
            list: JList<out AnsibleGroupScript>, value: AnsibleGroupScript,
            index: Int, selected: Boolean, hasFocus: Boolean
        ) {
            icon = PluginIcons.Script
            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val preview = value.content.lines().firstOrNull { it.trim().isNotEmpty() && !it.startsWith("#") }
            if (preview != null) {
                val short = if (preview.length > 40) preview.take(40) + "…" else preview
                append("  $short", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    inner class AnsibleTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is CheckableGroup -> {
                    icon = PluginIcons.Folder
                    append(obj.group.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  (${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CheckableConfig -> {
                    icon = PluginIcons.SshConnection
                    append(
                        if (obj.checked) "\u2611  " else "\u2610  ",
                        if (obj.checked) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        else SimpleTextAttributes.GRAYED_ATTRIBUTES
                    )
                    append(obj.config.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${obj.config.host}:${obj.config.port}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
