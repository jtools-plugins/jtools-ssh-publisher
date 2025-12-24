package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.SshConnectionManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class AddItemDialog(
    private val project: Project,
    private val existingConfig: SshConfig? = null,
    private val defaultGroup: String? = null,
    private val onSaved: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val groupCombo = ComboBox<String>().apply { isEditable = true }
    private val nameField = JBTextField(existingConfig?.name ?: "")
    private val hostField = JBTextField(existingConfig?.host ?: "127.0.0.1")
    private val portField = JSpinner(SpinnerNumberModel(existingConfig?.port ?: 22, 1, 65535, 1))
    private val usernameField = JBTextField(existingConfig?.username ?: "root")
    private val passwordField = JBPasswordField().apply { text = existingConfig?.password ?: "" }
    private val privateKeyArea = JBTextArea(existingConfig?.privateKey ?: "", 4, 30)
    private val passphraseField = JBPasswordField().apply { text = existingConfig?.passphrase ?: "" }
    private val remoteDirField = JBTextField(existingConfig?.remoteDir ?: "/tmp")

    private val authTabs = JBTabbedPane()
    private var currentAuthType = existingConfig?.authType ?: SshConfig.AuthType.PASSWORD

    private val preScriptsTableModel = ScriptTableModel()
    private val postScriptsTableModel = ScriptTableModel()
    private val preScriptsTable = JBTable(preScriptsTableModel)
    private val postScriptsTable = JBTable(postScriptsTableModel)

    init {
        title = if (existingConfig == null) "新建SSH配置" else "编辑SSH配置"
        setOKButtonText("保存")
        setCancelButtonText("取消")
        loadGroups()
        loadScripts()
        init()
    }

    private fun loadGroups() {
        val groups = SshConfigService.getConfigsByGroup().keys.toMutableList()
        if (groups.isEmpty()) groups.add("默认")
        groupCombo.model = CollectionComboBoxModel(groups)
        groupCombo.selectedItem = existingConfig?.group ?: defaultGroup ?: groups.firstOrNull() ?: "默认"
    }

    private fun loadScripts() {
        existingConfig?.let { config ->
            SshConfigService.getPreScripts(config.id).forEach { preScriptsTableModel.addScript(it) }
            SshConfigService.getPostScripts(config.id).forEach { postScriptsTableModel.addScript(it) }
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(3)
        }

        // 基本信息
        var row = 0
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        mainPanel.add(JBLabel("分组:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        mainPanel.add(groupCombo, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        mainPanel.add(JBLabel("名称:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        mainPanel.add(nameField, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        mainPanel.add(JBLabel("主机:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        mainPanel.add(JPanel(BorderLayout(5, 0)).apply {
            add(hostField, BorderLayout.CENTER)
            add(JPanel(BorderLayout(3, 0)).apply {
                add(JBLabel("端口:"), BorderLayout.WEST)
                add(portField.apply { preferredSize = Dimension(70, 28) }, BorderLayout.CENTER)
            }, BorderLayout.EAST)
        }, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        mainPanel.add(JBLabel("远程目录:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        mainPanel.add(remoteDirField, gbc)

        // 认证方式Tab
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.BOTH

        val passwordPanel = JPanel(GridBagLayout()).apply {
            val g = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(3)
            }
            g.gridx = 0; g.gridy = 0; g.weightx = 0.0
            add(JBLabel("用户名:"), g)
            g.gridx = 1; g.weightx = 1.0
            add(usernameField, g)

            g.gridx = 0; g.gridy = 1; g.weightx = 0.0
            add(JBLabel("密码:"), g)
            g.gridx = 1; g.weightx = 1.0
            add(passwordField, g)
        }

        val keyPanel = JPanel(GridBagLayout()).apply {
            val g = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(3)
            }
            g.gridx = 0; g.gridy = 0; g.weightx = 0.0
            add(JBLabel("用户名:"), g)
            g.gridx = 1; g.weightx = 1.0
            val keyUsernameField = JBTextField(existingConfig?.username ?: "root")
            keyUsernameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
                private fun sync() { if (authTabs.selectedIndex == 1) usernameField.text = keyUsernameField.text }
            })
            add(keyUsernameField, g)

            g.gridx = 0; g.gridy = 1; g.weightx = 0.0; g.anchor = GridBagConstraints.NORTHWEST
            add(JBLabel("私钥:"), g)
            g.gridx = 1; g.weightx = 1.0; g.fill = GridBagConstraints.BOTH; g.weighty = 1.0
            add(JBScrollPane(privateKeyArea).apply { preferredSize = Dimension(300, 80) }, g)

            g.gridx = 0; g.gridy = 2; g.weightx = 0.0; g.weighty = 0.0; g.fill = GridBagConstraints.HORIZONTAL
            add(JBLabel("私钥密码:"), g)
            g.gridx = 1; g.weightx = 1.0
            add(passphraseField, g)
        }

        authTabs.addTab("密码认证", passwordPanel)
        authTabs.addTab("密钥认证", keyPanel)
        authTabs.selectedIndex = if (currentAuthType == SshConfig.AuthType.PASSWORD) 0 else 1
        authTabs.addChangeListener {
            currentAuthType = if (authTabs.selectedIndex == 0) SshConfig.AuthType.PASSWORD else SshConfig.AuthType.KEY
        }
        mainPanel.add(authTabs, gbc)

        // 脚本Tab
        row++
        gbc.gridy = row; gbc.weighty = 1.0
        val scriptTabs = JBTabbedPane().apply {
            addTab("前置脚本", createScriptPanel(preScriptsTable, preScriptsTableModel, ScriptConfig.ScriptType.PRE))
            addTab("后置脚本", createScriptPanel(postScriptsTable, postScriptsTableModel, ScriptConfig.ScriptType.POST))
        }
        mainPanel.add(scriptTabs, gbc)

        // 测试连接按钮
        row++
        gbc.gridy = row; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        mainPanel.add(JPanel(BorderLayout()).apply {
            add(JButton("测试连接", AllIcons.Actions.Execute).apply {
                addActionListener { testConnection() }
            }, BorderLayout.EAST)
        }, gbc)

        return JBScrollPane(mainPanel).apply {
            preferredSize = Dimension(620, 720)
            border = JBUI.Borders.empty(5)
        }
    }

    private fun createScriptPanel(table: JBTable, model: ScriptTableModel, scriptType: ScriptConfig.ScriptType): JComponent {
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        table.columnModel.getColumn(0).preferredWidth = 40
        table.columnModel.getColumn(0).maxWidth = 40
        table.columnModel.getColumn(1).preferredWidth = 120
        table.rowHeight = 24

        return ToolbarDecorator.createDecorator(table)
            .setAddAction {
                ScriptEditDialog(project, null, scriptType).let { dialog ->
                    if (dialog.showAndGet()) {
                        dialog.getScript()?.let { model.addScript(it) }
                    }
                }
            }
            .setRemoveAction {
                val row = table.selectedRow
                if (row >= 0) model.removeScript(row)
            }
            .setEditAction {
                val row = table.selectedRow
                if (row >= 0) {
                    val script = model.getScript(row)
                    ScriptEditDialog(project, script, scriptType).let { dialog ->
                        if (dialog.showAndGet()) {
                            dialog.getScript()?.let { model.updateScript(row, it) }
                        }
                    }
                }
            }
            .setPreferredSize(Dimension(450, 120))
            .createPanel()
    }

    private fun buildConfig(): SshConfig {
        return SshConfig(
            id = existingConfig?.id ?: java.util.UUID.randomUUID().toString(),
            group = (groupCombo.selectedItem as? String)?.trim() ?: "默认",
            name = nameField.text.trim(),
            host = hostField.text.trim(),
            port = portField.value as Int,
            username = usernameField.text.trim(),
            authType = currentAuthType,
            password = String(passwordField.password),
            privateKey = privateKeyArea.text,
            passphrase = String(passphraseField.password),
            remoteDir = remoteDirField.text.trim()
        )
    }

    private fun testConnection() {
        val config = buildConfig()
        if (config.name.isEmpty() || config.host.isEmpty()) {
            Messages.showErrorDialog(project, "请填写名称和主机地址", "错误")
            return
        }

        Thread {
            val manager = SshConnectionManager()
            try {
                val success = manager.connect(config)
                SwingUtilities.invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "连接成功!", "测试连接")
                    } else {
                        Messages.showErrorDialog(project, "连接失败，请检查配置", "测试连接")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "连接失败: ${e.message}", "测试连接")
                }
            } finally {
                manager.close()
            }
        }.start()
    }

    override fun doOKAction() {
        val config = buildConfig()

        if (config.name.isEmpty()) {
            Messages.showErrorDialog(project, "请输入名称", "错误")
            return
        }

        if (config.host.isEmpty()) {
            Messages.showErrorDialog(project, "请输入主机地址", "错误")
            return
        }
/*        // 检查 group+name 是否重复（排除自己）
        println("[DEBUG] Checking duplicate: group=${config.group}, name=${config.name}, excludeId=${existingConfig?.id}")
        if (SshConfigService.existsByGroupAndName(config.group, config.name, existingConfig?.id)) {
            Messages.showErrorDialog(project, "分组 \"${config.group}\" 下已存在名称为 \"${config.name}\" 的配置", "错误")
            return
        }*/

        if (existingConfig == null) {
            SshConfigService.addConfig(config)
        } else {
            SshConfigService.updateConfig(config)
            SshConfigService.getScriptsByConfigId(config.id).forEach {
                SshConfigService.removeScript(it.id)
            }
        }

        preScriptsTableModel.getScripts().forEach {
            SshConfigService.addScript(it.copy(sshConfigId = config.id))
        }
        postScriptsTableModel.getScripts().forEach {
            SshConfigService.addScript(it.copy(sshConfigId = config.id))
        }

        onSaved?.invoke()
        super.doOKAction()
    }
}

class ScriptTableModel : AbstractTableModel() {
    private val scripts = mutableListOf<ScriptConfig>()
    private val columns = arrayOf("启用", "名称", "内容预览")

    override fun getRowCount() = scripts.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val script = scripts[rowIndex]
        return when (columnIndex) {
            0 -> script.enabled
            1 -> script.name
            2 -> script.content.replace("\n", " ").take(50)
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0 && aValue is Boolean) {
            scripts[rowIndex] = scripts[rowIndex].copy(enabled = aValue)
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    fun addScript(script: ScriptConfig) {
        scripts.add(script)
        fireTableRowsInserted(scripts.size - 1, scripts.size - 1)
    }

    fun removeScript(row: Int) {
        scripts.removeAt(row)
        fireTableRowsDeleted(row, row)
    }

    fun updateScript(row: Int, script: ScriptConfig) {
        scripts[row] = script
        fireTableRowsUpdated(row, row)
    }

    fun getScript(row: Int) = scripts[row]
    fun getScripts() = scripts.toList()
}

class ScriptEditDialog(
    private val project: Project,
    private val existingScript: ScriptConfig?,
    private val scriptType: ScriptConfig.ScriptType
) : DialogWrapper(project, true), Disposable {

    private val nameField = JBTextField(existingScript?.name ?: "")
    private lateinit var contentEditor: com.lhstack.ssh.component.MultiLanguageTextField

    init {
        title = if (existingScript == null) "添加脚本" else "编辑脚本"
        init()
    }

    override fun createCenterPanel(): JComponent {
        // 尝试获取Shell文件类型，如果不存在则使用PlainText
        val shellFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getFileTypeByExtension("sh") as? com.intellij.openapi.fileTypes.LanguageFileType
            ?: com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE
        
        contentEditor = com.lhstack.ssh.component.MultiLanguageTextField(
            shellFileType,
            project,
            existingScript?.content ?: "#!/bin/bash\n",
            isLineNumbersShown = true,
            viewer = false
        )
        Disposer.register(disposable, contentEditor)

        return JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(5)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            add(JBLabel("脚本名称:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(nameField, gbc)

            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST
            add(JBLabel("脚本内容:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
            add(contentEditor.apply { preferredSize = Dimension(500, 300) }, gbc)

            preferredSize = Dimension(600, 420)
            border = JBUI.Borders.empty(10)
        }
    }

    fun getScript(): ScriptConfig? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return null
        return ScriptConfig(
            id = existingScript?.id ?: System.currentTimeMillis().toString(),
            sshConfigId = existingScript?.sshConfigId ?: "",
            name = name,
            scriptType = scriptType,
            content = contentEditor.text,
            enabled = existingScript?.enabled ?: true
        )
    }

    override fun dispose() {
        super.dispose()
    }
}
