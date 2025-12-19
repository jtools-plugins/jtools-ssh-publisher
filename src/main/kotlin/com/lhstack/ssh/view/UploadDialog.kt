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
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.component.MultiLanguageTextField
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.SshConfigService
import com.lhstack.ssh.service.SshConnectionManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * 文件上传对话框
 */
class UploadDialog(
    private val project: Project,
    private val config: SshConfig
) : DialogWrapper(project, true), Disposable {

    private val localFileField = JBTextField()
    private val remotePathField = JBTextField(config.remoteDir)
    private val remoteFileNameField = JBTextField()  // 远程文件名（可选）
    private val progressBar = JProgressBar(0, 100)
    private val statusLabel = JBLabel("准备上传")
    private val logArea = JBTextArea(8, 50).apply { isEditable = false }

    private val preScriptsModel = UploadScriptTableModel()
    private val postScriptsModel = UploadScriptTableModel()
    private val preScriptsTable = JBTable(preScriptsModel)
    private val postScriptsTable = JBTable(postScriptsModel)

    private lateinit var tempPreScriptEditor: MultiLanguageTextField
    private lateinit var tempPostScriptEditor: MultiLanguageTextField

    private val shellFileType: LanguageFileType by lazy {
        FileTypeManager.getInstance().getFileTypeByExtension("sh") as? LanguageFileType
            ?: PlainTextFileType.INSTANCE
    }

    init {
        title = "上传文件 - ${config.name} (${config.host})"
        setSize(650, 750)
        setOKButtonText("开始上传")
        setCancelButtonText("关闭")
        loadScripts()
        init()
    }

    private fun loadScripts() {
        SshConfigService.getPreScripts(config.id).forEach {
            preScriptsModel.addScript(it, it.enabled)
        }
        SshConfigService.getPostScripts(config.id).forEach {
            postScriptsModel.addScript(it, it.enabled)
        }
    }

    override fun createCenterPanel(): JComponent {
        // 创建Shell编辑器
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
                            // 自动填充远程文件名（用户可修改）
                            if (remoteFileNameField.text.isEmpty()) {
                                remoteFileNameField.text = vf.name
                            }
                        }
                    }
                }, BorderLayout.EAST)
            })
            .addLabeledComponent(JBLabel("远程目录:"), remotePathField)
            .addLabeledComponent(JBLabel("远程文件名:"), remoteFileNameField.apply {
                toolTipText = "留空则使用本地文件名，可自定义避免中文乱码"
            })
            .panel

        // 脚本选择Tab
        val scriptTabs = JBTabbedPane().apply {
            addTab("前置脚本", createScriptSelectPanel(preScriptsTable, tempPreScriptEditor, "上传前执行"))
            addTab("后置脚本", createScriptSelectPanel(postScriptsTable, tempPostScriptEditor, "上传后执行"))
        }

        // 进度面板
        val progressPanel = JPanel(BorderLayout(5, 5)).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
        }

        // 日志面板
        val logPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("执行日志:"), BorderLayout.NORTH)
            add(JBScrollPane(logArea), BorderLayout.CENTER)
            preferredSize = Dimension(600, 150)
        }

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(filePanel, BorderLayout.NORTH)
            add(scriptTabs, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 10)).apply {
                add(progressPanel, BorderLayout.NORTH)
                add(logPanel, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
    }

    private fun createScriptSelectPanel(
        table: JBTable,
        tempEditor: MultiLanguageTextField,
        tempLabel: String
    ): JComponent {
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        table.rowHeight = 24
        
        // 调整列宽：选择列固定宽度，名称列适当宽度，内容预览列自动填充
        table.columnModel.getColumn(0).apply {
            preferredWidth = 40
            maxWidth = 40
            minWidth = 40
        }
        table.columnModel.getColumn(1).apply {
            preferredWidth = 150
            minWidth = 100
        }
        // 第三列（内容预览）自动填充剩余空间
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        val tablePanel = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableRemoveAction()
            .createPanel().apply {
                preferredSize = Dimension(600, 120)
            }

        val tempPanel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("临时脚本 ($tempLabel，不保存):"), BorderLayout.NORTH)
            add(tempEditor.apply { preferredSize = Dimension(600, 100) }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, 10)).apply {
            add(tablePanel, BorderLayout.NORTH)
            add(tempPanel, BorderLayout.CENTER)
        }
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
            Messages.showErrorDialog(project, "请输入远程路径", "错误")
            return
        }

        isOKActionEnabled = false
        logArea.text = ""

        Thread {
            val manager = SshConnectionManager()
            try {
                appendLog("正在连接 ${config.host}:${config.port}...")
                updateStatus("正在连接...")

                if (!manager.connect(config)) {
                    showError("连接失败，请检查配置")
                    return@Thread
                }
                appendLog("✓ 连接成功")

                // 执行选中的前置脚本
                executeScripts(manager, preScriptsModel, "前置")

                // 执行临时前置脚本
                val tempPreScript = tempPreScriptEditor.text.trim()
                if (tempPreScript.isNotEmpty()) {
                    appendLog("执行临时前置脚本...")
                    val result = manager.executeCommand(tempPreScript)
                    if (result.isNotBlank()) appendLog(result)
                }

                // 上传文件
                updateStatus("正在上传...")
                
                // 使用自定义文件名或原文件名
                val remoteFileName = remoteFileNameField.text.trim().ifEmpty { localFile.name }
                appendLog("开始上传: ${localFile.name} -> $remoteFileName (${localFile.length() / 1024}KB)")

                val fullRemotePath = if (remotePath.endsWith("/")) {
                    remotePath + remoteFileName
                } else {
                    "$remotePath/$remoteFileName"
                }

                val success = manager.uploadFile(localFile, fullRemotePath) { uploaded, total ->
                    val percent = (uploaded * 100 / total).toInt()
                    SwingUtilities.invokeLater {
                        progressBar.value = percent
                        statusLabel.text = "上传中: $percent%"
                    }
                }

                if (!success) {
                    showError("上传失败")
                    return@Thread
                }
                appendLog("✓ 上传成功: $fullRemotePath")

                // 执行选中的后置脚本
                executeScripts(manager, postScriptsModel, "后置")

                // 执行临时后置脚本
                val tempPostScript = tempPostScriptEditor.text.trim()
                if (tempPostScript.isNotEmpty()) {
                    appendLog("执行临时后置脚本...")
                    val result = manager.executeCommand(tempPostScript)
                    if (result.isNotBlank()) appendLog(result)
                }

                SwingUtilities.invokeLater {
                    appendLog("\n===== 全部完成 =====")
                    updateStatus("上传完成!")
                    progressBar.value = 100
                    isOKActionEnabled = true
                }

            } catch (e: Exception) {
                appendLog("✗ 错误: ${e.message}")
                showError("错误: ${e.message}")
            } finally {
                manager.close()
            }
        }.start()
    }

    private fun executeScripts(manager: SshConnectionManager, model: UploadScriptTableModel, type: String) {
        model.getSelectedScripts().forEach { script ->
            if (script.content.isNotEmpty()) {
                appendLog("执行${type}脚本: ${script.name}")
                updateStatus("执行${type}脚本: ${script.name}")
                try {
                    val result = manager.executeCommand(script.content)
                    if (result.isNotBlank()) appendLog(result)
                } catch (e: Exception) {
                    appendLog("✗ 脚本执行失败: ${e.message}")
                }
            }
        }
    }

    private fun updateStatus(text: String) {
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    private fun appendLog(text: String) {
        SwingUtilities.invokeLater {
            logArea.append("$text\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            appendLog("✗ $message")
            updateStatus("失败")
            isOKActionEnabled = true
        }
    }

    override fun dispose() {
        super.dispose()
    }
}

/**
 * 上传脚本表格模型
 */
class UploadScriptTableModel : AbstractTableModel() {
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

    fun getSelectedScripts(): List<ScriptConfig> = items.filter { it.selected }.map { it.script }
}
