package com.lhstack.ssh.view

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.model.SshGroup
import java.awt.*
import javax.swing.*

/**
 * 新建 / 编辑 Ansible 脚本弹窗
 *
 * @param groups         可选分组列表
 * @param defaultGroupId 默认选中的分组 id
 * @param initialName    初始名称（编辑时传旧名）
 * @param initialContent 初始内容（编辑时传旧内容）
 * @param isEdit         true = 编辑模式（分组展示但不可改）；false = 新建模式
 */
class AnsibleScriptEditDialog(
    private val project: Project,
    private val groups: List<SshGroup>,
    private val defaultGroupId: String? = null,
    initialName: String = "",
    initialContent: String = "#!/bin/bash\n",
    private val isEdit: Boolean = false
) : DialogWrapper(project, true) {

    private val groupCombo = JComboBox(groups.map { it.name }.toTypedArray())
    private val nameField = JBTextField(initialName, 30)

    private val shellFileType: LanguageFileType =
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE

    private val scriptEditor = MultiLanguageTextField(
        shellFileType, project, initialContent, isLineNumbersShown = true
    ).also { Disposer.register(disposable, it) }

    init {
        title = if (isEdit) "编辑脚本" else "新建脚本"
        val idx = groups.indexOfFirst { it.id == defaultGroupId }.coerceAtLeast(0)
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        // 表单区：分组 + 名称
        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(3, 4, 3, 4)
            fill = GridBagConstraints.HORIZONTAL
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        formPanel.add(JBLabel("分组："), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        if (isEdit) {
            val groupName = groups.firstOrNull { it.id == defaultGroupId }?.name ?: ""
            formPanel.add(JBLabel(groupName), gbc)
        } else {
            formPanel.add(groupCombo, gbc)
        }

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        formPanel.add(JBLabel("名称："), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        formPanel.add(nameField, gbc)

        panel.add(formPanel, BorderLayout.NORTH)

        // 脚本内容编辑器
        val editorWrapper = JPanel(BorderLayout()).apply {
            add(JBLabel("脚本内容：").apply {
                border = BorderFactory.createEmptyBorder(6, 0, 4, 0)
            }, BorderLayout.NORTH)
            add(scriptEditor, BorderLayout.CENTER)
            preferredSize = Dimension(660, 420)
        }
        panel.add(editorWrapper, BorderLayout.CENTER)

        return panel
    }

    /** 获取选中的分组 id */
    fun getSelectedGroupId(): String =
        groups.getOrNull(groupCombo.selectedIndex)?.id ?: defaultGroupId ?: ""

    fun getScriptName(): String = nameField.text.trim()
    fun getScriptContent(): String = scriptEditor.text

    override fun doOKAction() {
        if (getScriptName().isBlank()) {
            JOptionPane.showMessageDialog(
                contentPanel, "脚本名称不能为空", "错误", JOptionPane.ERROR_MESSAGE
            )
            return
        }
        super.doOKAction()
    }
}
