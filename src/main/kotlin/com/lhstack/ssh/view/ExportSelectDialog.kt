package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.UploadTemplate
import com.lhstack.ssh.service.SshConfigService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree

/**
 * 导出选择对话框 - 选择要导出的SSH配置和上传模板
 */
class ExportSelectDialog(
    private val project: Project
) : DialogWrapper(project, true) {

    private lateinit var checkboxTree: CheckboxTree
    private val rootNode = CheckedTreeNode("全部配置")
    private val configNodeMap = mutableMapOf<String, CheckedTreeNode>()
    private val templateNodeMap = mutableMapOf<String, CheckedTreeNode>()

    init {
        title = "选择要导出的配置"
        setOKButtonText("确定")
        setCancelButtonText("取消")
        init()
    }

    override fun createCenterPanel(): JComponent {
        // SSH配置分组
        val sshRootNode = CheckedTreeNode("SSH连接")
        val configsByGroup = SshConfigService.getConfigsByGroup()
        configsByGroup.toSortedMap().forEach { (group, configs) ->
            val groupNode = CheckedTreeNode(group)
            configs.sortedBy { it.name }.forEach { config ->
                val configNode = CheckedTreeNode(config)
                configNode.isChecked = false
                configNodeMap[config.id] = configNode
                groupNode.add(configNode)
            }
            groupNode.isChecked = false
            sshRootNode.add(groupNode)
        }
        sshRootNode.isChecked = false
        rootNode.add(sshRootNode)

        // 上传模板分组
        val templateRootNode = CheckedTreeNode("上传模板")
        val templatesByGroup = SshConfigService.getUploadTemplatesByGroup()
        templatesByGroup.toSortedMap().forEach { (group, templates) ->
            val groupNode = CheckedTreeNode(group)
            templates.sortedBy { it.name }.forEach { template ->
                val templateNode = CheckedTreeNode(template)
                templateNode.isChecked = false
                templateNodeMap[template.id] = templateNode
                groupNode.add(templateNode)
            }
            groupNode.isChecked = false
            templateRootNode.add(groupNode)
        }
        templateRootNode.isChecked = false
        rootNode.add(templateRootNode)
        
        rootNode.isChecked = false

        // 创建复选框树
        checkboxTree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer() {
            override fun customizeRenderer(
                tree: JTree?,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = value as? CheckedTreeNode ?: return
                when (val userObject = node.userObject) {
                    is SshConfig -> {
                        textRenderer.icon = AllIcons.Nodes.Plugin
                        textRenderer.append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        textRenderer.append("  ${userObject.host}:${userObject.port}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is UploadTemplate -> {
                        textRenderer.icon = AllIcons.Actions.Upload
                        textRenderer.append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val sshConfig = SshConfigService.getConfigById(userObject.sshConfigId)
                        val serverName = sshConfig?.name ?: "未知服务器"
                        textRenderer.append("  → $serverName", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is String -> {
                        textRenderer.icon = AllIcons.Nodes.Folder
                        textRenderer.append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        textRenderer.append("  (${node.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }, rootNode)

        // 展开所有节点
        for (i in 0 until checkboxTree.rowCount) {
            checkboxTree.expandRow(i)
        }

        // 按钮面板
        val buttonPanel = JPanel().apply {
            add(javax.swing.JButton("全选").apply {
                addActionListener { selectAll(true) }
            })
            add(javax.swing.JButton("取消全选").apply {
                addActionListener { selectAll(false) }
            })
        }

        return JPanel(BorderLayout(0, 5)).apply {
            border = JBUI.Borders.empty(10)
            add(buttonPanel, BorderLayout.NORTH)
            add(JBScrollPane(checkboxTree).apply {
                preferredSize = Dimension(450, 400)
            }, BorderLayout.CENTER)
        }
    }

    private fun selectAll(selected: Boolean) {
        fun setChecked(node: CheckedTreeNode, checked: Boolean) {
            node.isChecked = checked
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? CheckedTreeNode
                if (child != null) {
                    setChecked(child, checked)
                }
            }
        }
        setChecked(rootNode, selected)
        checkboxTree.repaint()
    }

    /**
     * 获取选中的配置ID列表
     */
    fun getSelectedConfigIds(): List<String> {
        return configNodeMap.filter { it.value.isChecked }
            .map { it.key }
    }

    /**
     * 获取选中的上传模板ID列表
     */
    fun getSelectedTemplateIds(): List<String> {
        return templateNodeMap.filter { it.value.isChecked }
            .map { it.key }
    }
}
