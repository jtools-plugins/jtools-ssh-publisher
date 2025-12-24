package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.RemoteFileEditorService
import com.lhstack.ssh.service.SshConnectionManager
import org.apache.sshd.sftp.client.SftpClient
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * SFTP 文件系统面板
 */
class SftpFileSystemPanel(
    private val config: SshConfig,
    private val project: Project
) : SimpleToolWindowPanel(true, true), Disposable {

    private val connectionManager = SshConnectionManager()
    private val executor = Executors.newFixedThreadPool(2)
    private lateinit var remoteFileEditorService: RemoteFileEditorService

    private val rootNode = DefaultMutableTreeNode(FileNode("/", true, 0, 0, ""))
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)

    private val pathField = JBTextField("/")
    private val statusLabel = JBLabel("未连接")
    private val progressBar = JProgressBar(0, 100).apply {
        isVisible = false
        isStringPainted = true
        preferredSize = java.awt.Dimension(150, 16)
    }
    private val cancelButton = JButton("取消").apply {
        isVisible = false
        addActionListener { cancelTransfer() }
    }

    private var currentPath = "/"
    
    @Volatile
    private var transferCancelled = false
    private var currentTransferFuture: java.util.concurrent.Future<*>? = null

    init {
        remoteFileEditorService = RemoteFileEditorService(project, connectionManager, config)
        initToolbar()
        initContent()
        connect()
    }

    private fun initToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("上级目录", "返回上级目录", AllIcons.Actions.MoveUp) {
                override fun actionPerformed(e: AnActionEvent) = goUp()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("刷新", "刷新当前目录", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("主目录", "返回主目录", AllIcons.Nodes.HomeFolder) {
                override fun actionPerformed(e: AnActionEvent) = navigateTo(config.remoteDir.ifEmpty { "/" })
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            addSeparator()
            add(object : AnAction("上传", "上传文件到当前目录", AllIcons.Actions.Upload) {
                override fun actionPerformed(e: AnActionEvent) = uploadFile()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("下载", "下载选中的文件", AllIcons.Actions.Download) {
                override fun actionPerformed(e: AnActionEvent) = downloadSelected()
                override fun update(e: AnActionEvent) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    val fileNode = node?.userObject as? FileNode
                    e.presentation.isEnabled = fileNode != null && !fileNode.isDirectory
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("新建文件夹", "在当前目录创建文件夹", AllIcons.Actions.NewFolder) {
                override fun actionPerformed(e: AnActionEvent) = createFolder()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("新建文件", "在当前目录创建文件", AllIcons.FileTypes.Text) {
                override fun actionPerformed(e: AnActionEvent) = createFile()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("删除", "删除选中的文件或文件夹", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) = deleteSelected()
                override fun update(e: AnActionEvent) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    val fileNode = node?.userObject as? FileNode
                    e.presentation.isEnabled = fileNode != null && fileNode.path != currentPath
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("sftp-toolbar", actionGroup, true)
        toolbar.targetComponent = this

        // 路径栏
        val pathPanel = JPanel(BorderLayout(5, 0)).apply {
            border = JBUI.Borders.empty(2, 5)
            add(JBLabel("路径:"), BorderLayout.WEST)
            add(pathField.apply {
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) navigateTo(text.trim())
                    }
                })
            }, BorderLayout.CENTER)
        }

        this.toolbar = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(pathPanel, BorderLayout.CENTER)
        }
    }

    private fun initContent() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = FileTreeRenderer()
            rowHeight = 24

            // 双击进入目录或打开文件编辑
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                        // 通过点击位置获取节点，而不是依赖选中状态
                        val treePath = tree.getPathForLocation(e.x, e.y) ?: return
                        val node = treePath.lastPathComponent as? DefaultMutableTreeNode ?: return
                        val fileNode = node.userObject as? FileNode ?: return
                        if (fileNode.isDirectory) {
                            navigateTo(fileNode.path)
                        } else {
                            // 双击文件，在编辑器中打开
                            openFileInEditor(fileNode)
                        }
                    }
                }

                override fun mousePressed(e: MouseEvent) = handlePopup(e)
                override fun mouseReleased(e: MouseEvent) = handlePopup(e)

                private fun handlePopup(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        val path = tree.getPathForLocation(e.x, e.y)
                        if (path != null) tree.selectionPath = path
                        showContextMenu(e)
                    }
                }
            })

            // 展开节点时懒加载
            addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
                override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val fileNode = node.userObject as? FileNode ?: return
                    if (fileNode.isDirectory && node.childCount == 1) {
                        val firstChild = node.getChildAt(0) as? DefaultMutableTreeNode
                        if (firstChild?.userObject == "loading") {
                            loadDirectory(fileNode.path, node)
                        }
                    }
                }
                override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) {}
            })
        }

        TreeSpeedSearch(tree)

        // 状态栏
        val statusBar = JPanel(BorderLayout(10, 0)).apply {
            border = JBUI.Borders.empty(3, 5)
            add(statusLabel, BorderLayout.WEST)
            add(JPanel(BorderLayout(5, 0)).apply {
                add(progressBar, BorderLayout.CENTER)
                add(cancelButton, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(JBLabel("${config.name} (${config.host})").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }, BorderLayout.EAST)
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }

        setContent(mainPanel)
    }

    private fun connect() {
        statusLabel.text = "正在连接..."
        statusLabel.icon = AllIcons.Process.Step_1
        executor.submit {
            try {
                if (connectionManager.connect(config)) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "已连接"
                        statusLabel.icon = AllIcons.General.InspectionsOK
                        navigateTo(config.remoteDir.ifEmpty { "/" })
                    }
                } else {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "连接失败"
                        statusLabel.icon = AllIcons.General.Error
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "错误: ${e.message}"
                    statusLabel.icon = AllIcons.General.Error
                }
            }
        }
    }

    private fun navigateTo(path: String) {
        val targetPath = when {
            path == "~" -> config.remoteDir.ifEmpty { "/" }
            path.startsWith("/") -> path
            else -> "$currentPath/$path".replace("//", "/")
        }

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val attrs = sftp.stat(targetPath)
                if (attrs.isDirectory) {
                    currentPath = targetPath
                    SwingUtilities.invokeLater {
                        pathField.text = currentPath
                        loadRootDirectory()
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "无法访问: $targetPath\n${e.message}", "错误")
                }
            }
        }
    }

    private fun loadRootDirectory() {
        statusLabel.text = "加载中..."
        statusLabel.icon = AllIcons.Process.Step_1

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val entries = sftp.readDir(currentPath)
                    .filter { it.filename != "." && it.filename != ".." }
                    .sortedWith(compareByDescending<SftpClient.DirEntry> { it.attributes.isDirectory }.thenBy { it.filename })

                SwingUtilities.invokeLater {
                    rootNode.removeAllChildren()
                    entries.forEach { entry ->
                        val filePath = "$currentPath/${entry.filename}".replace("//", "/")
                        val perms = formatPermissions(entry.attributes.permissions)
                        val node = DefaultMutableTreeNode(
                            FileNode(filePath, entry.attributes.isDirectory, entry.attributes.size,
                                entry.attributes.modifyTime?.toMillis() ?: 0, perms)
                        )
                        if (entry.attributes.isDirectory) {
                            node.add(DefaultMutableTreeNode("loading"))
                        }
                        rootNode.add(node)
                    }
                    treeModel.reload()
                    statusLabel.text = "${rootNode.childCount} 项"
                    statusLabel.icon = AllIcons.General.InspectionsOK
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "加载失败: ${e.message}"
                    statusLabel.icon = AllIcons.General.Error
                }
            }
        }
    }

    private fun loadDirectory(path: String, parentNode: DefaultMutableTreeNode) {
        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val entries = sftp.readDir(path)
                    .filter { it.filename != "." && it.filename != ".." }
                    .sortedWith(compareByDescending<SftpClient.DirEntry> { it.attributes.isDirectory }.thenBy { it.filename })

                SwingUtilities.invokeLater {
                    parentNode.removeAllChildren()
                    entries.forEach { entry ->
                        val filePath = "$path/${entry.filename}".replace("//", "/")
                        val perms = formatPermissions(entry.attributes.permissions)
                        val node = DefaultMutableTreeNode(
                            FileNode(filePath, entry.attributes.isDirectory, entry.attributes.size,
                                entry.attributes.modifyTime?.toMillis() ?: 0, perms)
                        )
                        if (entry.attributes.isDirectory) {
                            node.add(DefaultMutableTreeNode("loading"))
                        }
                        parentNode.add(node)
                    }
                    treeModel.nodeStructureChanged(parentNode)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { statusLabel.text = "加载失败" }
            }
        }
    }

    private fun formatPermissions(perms: Int): String {
        val sb = StringBuilder()
        sb.append(if (perms and 0x100 != 0) "r" else "-")
        sb.append(if (perms and 0x80 != 0) "w" else "-")
        sb.append(if (perms and 0x40 != 0) "x" else "-")
        sb.append(if (perms and 0x20 != 0) "r" else "-")
        sb.append(if (perms and 0x10 != 0) "w" else "-")
        sb.append(if (perms and 0x8 != 0) "x" else "-")
        sb.append(if (perms and 0x4 != 0) "r" else "-")
        sb.append(if (perms and 0x2 != 0) "w" else "-")
        sb.append(if (perms and 0x1 != 0) "x" else "-")
        return sb.toString()
    }

    private fun goUp() {
        if (currentPath != "/") {
            navigateTo(currentPath.substringBeforeLast("/").ifEmpty { "/" })
        }
    }

    private fun refresh() {
        // 检查连接状态，断开则重连
        if (!connectionManager.isConnected()) {
            statusLabel.text = "正在重连..."
            statusLabel.icon = AllIcons.Process.Step_1
            executor.submit {
                try {
                    if (connectionManager.connect(config)) {
                        SwingUtilities.invokeLater {
                            statusLabel.text = "已重连"
                            statusLabel.icon = AllIcons.General.InspectionsOK
                            loadRootDirectory()
                        }
                    } else {
                        SwingUtilities.invokeLater {
                            statusLabel.text = "重连失败"
                            statusLabel.icon = AllIcons.General.Error
                            Messages.showErrorDialog(project, "无法重新连接到服务器", "连接失败")
                        }
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "重连失败: ${e.message}"
                        statusLabel.icon = AllIcons.General.Error
                    }
                }
            }
        } else {
            loadRootDirectory()
        }
    }

    private fun uploadFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        descriptor.title = "选择要上传的文件"
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            val localFile = File(vf.path)
            val remotePath = "$currentPath/${localFile.name}".replace("//", "/")
            doUpload(localFile, remotePath)
        }
    }

    private fun doUpload(localFile: File, remotePath: String) {
        showProgress(true, "上传: ${localFile.name}")
        transferCancelled = false

        currentTransferFuture = executor.submit {
            val sftp = connectionManager.getSftpClient()
            var success = false
            try {
                val totalSize = localFile.length()
                var uploaded = 0L

                sftp.write(remotePath).use { output ->
                    localFile.inputStream().use { input ->
                        val buffer = ByteArray(32768)
                        var read: Int = 0
                        while (!transferCancelled && input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            uploaded += read
                            val percent = (uploaded * 100 / totalSize).toInt()
                            SwingUtilities.invokeLater {
                                progressBar.value = percent
                                progressBar.string = "$percent%"
                            }
                        }
                    }
                }
                success = !transferCancelled
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showProgress(false, if (transferCancelled) "上传已取消" else "上传失败")
                    if (!transferCancelled) {
                        Messages.showErrorDialog(project, "上传失败: ${e.message}", "错误")
                    }
                }
            } finally {
                // 在executor线程中删除未完成的文件
                if (transferCancelled || !success) {
                    try {
                        sftp.remove(remotePath)
                    } catch (_: Exception) {
                    }
                }
                SwingUtilities.invokeLater {
                    if (success) {
                        showProgress(false, "上传完成")
                        refresh()
                    } else if (transferCancelled) {
                        showProgress(false, "上传已取消")
                    }
                }
            }
        }
    }

    private fun downloadSelected() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val fileNode = node.userObject as? FileNode ?: return
        if (fileNode.isDirectory) {
            Messages.showInfoMessage(project, "暂不支持下载文件夹", "提示")
            return
        }

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        descriptor.title = "选择保存目录"
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            val saveDir = File(vf.path)
            val fileName = fileNode.name
            val localFile = File(saveDir, fileName)
            doDownload(fileNode.path, localFile, fileNode.size)
        }
    }

    private fun doDownload(remotePath: String, localFile: File, totalSize: Long) {
        showProgress(true, "下载: ${localFile.name}")
        transferCancelled = false
        
        currentTransferFuture = executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                var downloaded = 0L

                sftp.read(remotePath).use { input ->
                    localFile.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var read: Int = 0
                        while (!transferCancelled && input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                            SwingUtilities.invokeLater {
                                progressBar.value = percent
                                progressBar.string = "$percent%"
                            }
                        }
                    }
                }
                
                SwingUtilities.invokeLater {
                    if (transferCancelled) {
                        showProgress(false, "下载已取消")
                        // 删除未完成的文件
                        localFile.delete()
                    } else {
                        showProgress(false, "下载完成")
                        Messages.showInfoMessage(project, "已保存到:\n${localFile.absolutePath}", "下载完成")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showProgress(false, if (transferCancelled) "下载已取消" else "下载失败")
                    if (!transferCancelled) {
                        Messages.showErrorDialog(project, "下载失败: ${e.message}", "错误")
                    }
                    // 删除未完成的文件
                    localFile.delete()
                }
            }
        }
    }

    private fun createFolder() {
        val name = Messages.showInputDialog(project, "文件夹名称:", "新建文件夹", null) ?: return
        if (name.isBlank()) return

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                sftp.mkdir("$currentPath/$name".replace("//", "/"))
                SwingUtilities.invokeLater { refresh() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "创建失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun createFile() {
        val name = Messages.showInputDialog(project, "文件名称:", "新建文件", null) ?: return
        if (name.isBlank()) return

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val remotePath = "$currentPath/$name".replace("//", "/")
                // 创建空文件
                sftp.write(remotePath).use { }
                SwingUtilities.invokeLater { refresh() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "创建失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun deleteSelected() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val fileNode = node.userObject as? FileNode ?: return

        if (Messages.showYesNoDialog(project, "确定删除 \"${fileNode.name}\"?", "确认删除", Messages.getWarningIcon()) != Messages.YES) return

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                if (fileNode.isDirectory) sftp.rmdir(fileNode.path) else sftp.remove(fileNode.path)
                
                // 删除本地缓存文件
                if (!fileNode.isDirectory) {
                    remoteFileEditorService.deleteLocalCache(fileNode.path)
                }
                
                SwingUtilities.invokeLater {
                    (node.parent as? DefaultMutableTreeNode)?.let {
                        it.remove(node)
                        treeModel.nodeStructureChanged(it)
                    }
                    statusLabel.text = "已删除"
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "删除失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val fileNode = node?.userObject as? FileNode

        JPopupMenu().apply {
            if (fileNode?.isDirectory == true) {
                add(JMenuItem("打开", AllIcons.Actions.MenuOpen).apply {
                    addActionListener { navigateTo(fileNode.path) }
                })
                add(JMenuItem("上传到此目录", AllIcons.Actions.Upload).apply {
                    addActionListener { uploadToDirectory(fileNode.path) }
                })
                addSeparator()
            }
            if (fileNode != null && !fileNode.isDirectory) {
                add(JMenuItem("编辑", AllIcons.Actions.Edit).apply {
                    addActionListener { openFileInEditor(fileNode) }
                })
                add(JMenuItem("下载", AllIcons.Actions.Download).apply {
                    addActionListener { downloadSelected() }
                })
            }
            if (fileNode != null) {
                add(JMenuItem("重命名", AllIcons.Actions.Edit).apply {
                    addActionListener { renameFile(fileNode) }
                })
                add(JMenuItem("删除", AllIcons.Actions.GC).apply {
                    addActionListener { deleteSelected() }
                })
                addSeparator()
                add(JMenuItem("复制路径", AllIcons.Actions.Copy).apply {
                    addActionListener {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(fileNode.path), null)
                        statusLabel.text = "已复制路径"
                    }
                })
                add(JMenuItem("属性", AllIcons.Actions.Properties).apply {
                    addActionListener { showProperties(fileNode) }
                })
            }
        }.show(e.component, e.x, e.y)
    }

    private fun uploadToDirectory(targetDir: String) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            val localFile = File(vf.path)
            doUpload(localFile, "$targetDir/${localFile.name}".replace("//", "/"))
        }
    }

    private fun renameFile(fileNode: FileNode) {
        val newName = Messages.showInputDialog(project, "新名称:", "重命名", null, fileNode.name, null) ?: return
        if (newName.isBlank() || newName == fileNode.name) return

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                sftp.rename(fileNode.path, fileNode.path.substringBeforeLast("/") + "/$newName")
                SwingUtilities.invokeLater { refresh() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "重命名失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun showProperties(fileNode: FileNode) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        Messages.showInfoMessage(project, """
            名称: ${fileNode.name}
            路径: ${fileNode.path}
            类型: ${if (fileNode.isDirectory) "目录" else "文件"}
            大小: ${formatSize(fileNode.size)}
            权限: ${fileNode.permissions}
            修改时间: ${if (fileNode.modifyTime > 0) dateFormat.format(Date(fileNode.modifyTime)) else "-"}
        """.trimIndent(), "属性")
    }

    /**
     * 在 IDEA 编辑器中打开远程文件
     */
    private fun openFileInEditor(fileNode: FileNode) {
        if (fileNode.isDirectory) return
        
        // 检查文件大小，超过 10MB 提示
        if (fileNode.size > 10 * 1024 * 1024) {
            val result = Messages.showYesNoDialog(
                project,
                "文件较大 (${formatSize(fileNode.size)})，打开可能需要较长时间，是否继续？",
                "文件较大",
                Messages.getWarningIcon()
            )
            if (result != Messages.YES) return
        }
        
        statusLabel.text = "正在打开: ${fileNode.name}"
        statusLabel.icon = AllIcons.Process.Step_1
        
        remoteFileEditorService.openRemoteFile(fileNode.path, fileNode.name) { error ->
            statusLabel.text = error
            statusLabel.icon = AllIcons.General.Error
            Messages.showErrorDialog(project, error, "打开文件失败")
        }
        
        // 延迟恢复状态
        executor.submit {
            Thread.sleep(1000)
            SwingUtilities.invokeLater {
                if (statusLabel.text.startsWith("正在打开")) {
                    statusLabel.text = "已连接"
                    statusLabel.icon = AllIcons.General.InspectionsOK
                }
            }
        }
    }

    private fun showProgress(show: Boolean, msg: String) {
        progressBar.isVisible = show
        progressBar.value = 0
        cancelButton.isVisible = show
        statusLabel.text = msg
        statusLabel.icon = if (show) AllIcons.Process.Step_1 else AllIcons.General.InspectionsOK
    }

    private fun cancelTransfer() {
        transferCancelled = true
        currentTransferFuture?.cancel(true)
        statusLabel.text = "正在取消..."
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    override fun dispose() {
        executor.shutdownNow()
        remoteFileEditorService.dispose()
        connectionManager.close()
    }
}

data class FileNode(val path: String, val isDirectory: Boolean, val size: Long, val modifyTime: Long, val permissions: String) {
    val name: String get() = path.substringAfterLast("/").ifEmpty { "/" }
}

class FileTreeRenderer : ColoredTreeCellRenderer() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is FileNode -> {
                icon = if (obj.isDirectory) AllIcons.Nodes.Folder else getFileIcon(obj.name)
                append(obj.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${obj.permissions}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (!obj.isDirectory) {
                    append("  ${formatSize(obj.size)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                if (obj.modifyTime > 0) {
                    append("  ${dateFormat.format(Date(obj.modifyTime))}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            is String -> {
                icon = AllIcons.Process.Step_1
                append(obj, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private fun getFileIcon(name: String): Icon {
        val ext = name.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "java", "kt", "scala" -> AllIcons.FileTypes.Java
            "xml", "html", "htm" -> AllIcons.FileTypes.Xml
            "json" -> AllIcons.FileTypes.Json
            "yaml", "yml" -> AllIcons.FileTypes.Yaml
            "sh", "bash" -> AllIcons.FileTypes.Any_type
            "txt", "md", "log" -> AllIcons.FileTypes.Text
            "jar", "war", "zip", "tar", "gz" -> AllIcons.FileTypes.Archive
            "png", "jpg", "jpeg", "gif", "svg" -> AllIcons.FileTypes.Any_type
            else -> AllIcons.FileTypes.Any_type
        }
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }
}
