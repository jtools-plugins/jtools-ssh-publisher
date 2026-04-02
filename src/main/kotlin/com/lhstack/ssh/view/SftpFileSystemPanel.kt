package com.lhstack.ssh.view

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
import com.lhstack.ssh.PluginIcons
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.TransferTask
import com.lhstack.ssh.service.RemoteFileEditorService
import com.lhstack.ssh.service.SshConnectionManager
import com.lhstack.ssh.service.TransferTaskManager
import com.lhstack.ssh.util.ConfirmationContent
import com.lhstack.ssh.util.RemotePathItem
import com.lhstack.ssh.util.RemoteRiskOperationUtils
import com.lhstack.ssh.util.SftpTreeOperationUtils
import com.lhstack.ssh.util.UploadPlan
import com.lhstack.ssh.util.UploadPlanItem
import com.lhstack.ssh.util.UploadPathUtils
import com.lhstack.ssh.util.LatestRequestGuard
import org.apache.sshd.sftp.client.SftpClient
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.swing.*
import javax.swing.TransferHandler.TransferSupport
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
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
    private var remoteFileEditorService: RemoteFileEditorService =
        RemoteFileEditorService(project, connectionManager, config)

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

    private var currentPath = "/"
    private val remoteNodeFlavor = DataFlavor(RemoteTreeTransferData::class.java, "RemoteTreeTransferData")
    private val navigationGuard = LatestRequestGuard()
    private val uploadRefreshTargetsByTaskId = ConcurrentHashMap<String, Set<String>>()
    private val pendingRefreshDirectories = linkedSetOf<String>()
    private val uploadTaskListener = object : TransferTaskManager.TaskListener {
        override fun onTaskAdded(task: TransferTask) = Unit

        override fun onTaskUpdated(task: TransferTask) {
            if (task.type != TransferTask.TransferType.UPLOAD || task.config != config) {
                return
            }
            when (task.status) {
                TransferTask.TaskStatus.SUCCESS -> {
                    scheduleDirectoryRefreshes(uploadRefreshTargetsByTaskId.remove(task.id).orEmpty())
                }
                TransferTask.TaskStatus.FAILED,
                TransferTask.TaskStatus.STOPPED -> {
                    uploadRefreshTargetsByTaskId.remove(task.id)
                }
                else -> Unit
            }
        }

        override fun onTaskRemoved(task: TransferTask) {
            uploadRefreshTargetsByTaskId.remove(task.id)
        }
    }
    private val deferredRefreshTimer = javax.swing.Timer(250) { flushPendingDirectoryRefreshes() }.apply {
        isRepeats = false
    }

    init {
        TransferTaskManager.addListener(uploadTaskListener)
        initToolbar()
        initContent()
        connect()
    }

    private fun initToolbar() {
        val batchUploadAction = object : AnAction("批量上传", "批量上传多个文件到当前目录或所选目录", PluginIcons.BatchUpload) {
            override fun actionPerformed(e: AnActionEvent) = batchUploadFiles()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

        val createEntryAction = object : AnAction("新建", "在当前目录创建文件/文件夹", PluginIcons.NewFile) {
            override fun actionPerformed(e: AnActionEvent) = showCreateEntryDialog(currentPath)
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

        val batchCreateAction = object : AnAction("批量创建", "在当前目录批量创建文件/文件夹", PluginIcons.NewFolder) {
            override fun actionPerformed(e: AnActionEvent) = showBatchCreateDialog(currentPath)
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

        val deleteAction = object : AnAction("删除", "删除选中的文件或文件夹", PluginIcons.Delete) {
            override fun actionPerformed(e: AnActionEvent) = deleteSelected()

            override fun update(e: AnActionEvent) {
                val selectedNodes = getSelectedFileNodes()
                val visibleNodes = selectedNodes.filterNot { it.path == currentPath }
                val isBatch = visibleNodes.size > 1
                e.presentation.isEnabled = visibleNodes.isNotEmpty()
                e.presentation.text = if (isBatch) "批量删除" else "删除"
                e.presentation.description = if (isBatch) "递归删除选中的多个文件或文件夹" else "删除选中的文件或文件夹"
                e.presentation.icon = if (isBatch) PluginIcons.BatchDelete else PluginIcons.Delete
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("上级目录", "返回上级目录", PluginIcons.ParentDirectory) {
                override fun actionPerformed(e: AnActionEvent) = goUp()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("刷新", "刷新当前目录", PluginIcons.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : AnAction("主目录", "返回主目录", PluginIcons.Home) {
                override fun actionPerformed(e: AnActionEvent) = navigateTo(config.remoteDir.ifEmpty { "/" })
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            addSeparator()
            add(object : AnAction("上传", "上传文件到当前目录", PluginIcons.Upload) {
                override fun actionPerformed(e: AnActionEvent) = uploadFile()
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(batchUploadAction)
            add(object : AnAction("下载", "下载选中的文件", PluginIcons.Download) {
                override fun actionPerformed(e: AnActionEvent) = downloadSelected()
                override fun update(e: AnActionEvent) {
                    val selected = getSelectedFileNodes()
                    e.presentation.isEnabled = selected.size == 1 && !selected.first().isDirectory
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(createEntryAction)
            add(batchCreateAction)
            add(deleteAction)
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
            dragEnabled = true
            dropMode = DropMode.ON
            selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
            transferHandler = FileTreeTransferHandler()

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
                        if (path != null && !tree.isPathSelected(path)) {
                            tree.selectionPath = path
                        }
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
            add(JBLabel("${config.name} (${config.host})").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }, BorderLayout.EAST)
        }
        
        // 系统监控状态栏
        val systemMonitorBar = SystemMonitorBar(connectionManager)

        // 底部面板（状态栏 + 系统监控）
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(statusBar, BorderLayout.NORTH)
            add(systemMonitorBar, BorderLayout.SOUTH)
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }

        setContent(mainPanel)
        
        // 启动系统监控（连接成功后会自动开始获取数据）
        systemMonitorBar.start()
    }

    private fun connect() {
        statusLabel.text = "正在连接..."
        statusLabel.icon = PluginIcons.Pending
        executor.submit {
            try {
                if (connectionManager.connect(config)) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "已连接"
                        statusLabel.icon = PluginIcons.Success
                        navigateTo(config.remoteDir.ifEmpty { "/" })
                    }
                } else {
                    SwingUtilities.invokeLater {
                        statusLabel.text = connectionManager.lastErrorMessage ?: "连接失败"
                        statusLabel.icon = PluginIcons.Error
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "错误: ${e.message}"
                    statusLabel.icon = PluginIcons.Error
                }
            }
        }
    }

    private fun navigateTo(path: String) {
        val token = navigationGuard.nextToken()
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
                    SwingUtilities.invokeLater {
                        if (!navigationGuard.isLatest(token)) {
                            return@invokeLater
                        }
                        currentPath = targetPath
                        pathField.text = targetPath
                        loadRootDirectory(targetPath, token)
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (!navigationGuard.isLatest(token)) {
                        return@invokeLater
                    }
                    Messages.showErrorDialog(project, "无法访问: $targetPath\n${e.message}", "错误")
                }
            }
        }
    }

    private fun loadRootDirectory(path: String = currentPath, token: Long? = null) {
        statusLabel.text = "加载中..."
        statusLabel.icon = PluginIcons.Pending

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val entries = sftp.readDir(path)
                    .filter { it.filename != "." && it.filename != ".." }
                    .sortedWith(compareByDescending<SftpClient.DirEntry> { it.attributes.isDirectory }.thenBy { it.filename })

                SwingUtilities.invokeLater {
                    if (token != null && !navigationGuard.isLatest(token)) {
                        return@invokeLater
                    }
                    rootNode.removeAllChildren()
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
                        rootNode.add(node)
                    }
                    treeModel.reload()
                    statusLabel.text = "${rootNode.childCount} 项"
                    statusLabel.icon = PluginIcons.Success
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (token != null && !navigationGuard.isLatest(token)) {
                        return@invokeLater
                    }
                    statusLabel.text = "加载失败: ${e.message}"
                    statusLabel.icon = PluginIcons.Error
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
            statusLabel.icon = PluginIcons.Pending
            executor.submit {
                try {
                    if (connectionManager.connect(config)) {
                        SwingUtilities.invokeLater {
                            statusLabel.text = "已重连"
                            statusLabel.icon = PluginIcons.Success
                            loadRootDirectory()
                        }
                    } else {
                        SwingUtilities.invokeLater {
                            statusLabel.text = connectionManager.lastErrorMessage ?: "重连失败"
                            statusLabel.icon = PluginIcons.Error
                            Messages.showErrorDialog(project, connectionManager.lastErrorMessage ?: "无法重新连接到服务器", "连接失败")
                        }
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "重连失败: ${e.message}"
                        statusLabel.icon = PluginIcons.Error
                    }
                }
            }
        } else {
            loadRootDirectory()
        }
    }

    private fun uploadFile() {
        val targetDirectory = resolveBatchUploadTargetDirectory()
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        descriptor.title = "选择要上传的文件"
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            queueUploadPlan(
                SftpTreeOperationUtils.buildUploadPlan(
                    sources = listOf(File(vf.path)),
                    targetDirectory = targetDirectory
                ),
                targetDirectory
            )
        }
    }

    private fun batchUploadFiles(targetDirectory: String = resolveBatchUploadTargetDirectory()) {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().apply {
            title = "选择要上传的文件"
        }
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            val localFiles = files.map { File(it.path) }.filter { it.exists() }
            queueUploadPlan(SftpTreeOperationUtils.buildUploadPlan(localFiles, targetDirectory), targetDirectory)
        }
    }

    private fun resolveBatchUploadTargetDirectory(): String {
        val selected = getSelectedFileNodes()
        val primary = getPrimarySelectedFileNode()
        return when {
            primary?.isDirectory == true -> primary.path
            else -> selected.firstOrNull { it.isDirectory }?.path ?: currentPath
        }
    }

    private fun queueUploadPlan(plan: UploadPlan, refreshTargetDirectory: String) {
        if (plan.directories.isEmpty() && plan.files.isEmpty()) {
            return
        }

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                plan.directories.sorted().forEach { ensureRemoteDirectory(sftp, it) }
                val tasks = plan.files.map { createUploadTask(it) }
                registerUploadRefreshTargets(tasks, refreshTargetDirectory)

                SwingUtilities.invokeLater {
                    when {
                        tasks.isEmpty() -> {
                            statusLabel.text = "已创建 ${plan.directories.size} 个目录"
                            statusLabel.icon = PluginIcons.Success
                            refreshVisibleDirectory(refreshTargetDirectory)
                        }
                        tasks.size == 1 -> {
                            TransferTaskManager.addTask(tasks.first())
                            statusLabel.text = "已添加上传任务: ${tasks.first().localFile.name}"
                        }
                        else -> {
                            TransferTaskManager.addTasks(tasks)
                            statusLabel.text = "已添加 ${tasks.size} 个上传任务"
                        }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "上传准备失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun registerUploadRefreshTargets(tasks: List<TransferTask>, refreshTargetDirectory: String) {
        tasks.forEach { task ->
            uploadRefreshTargetsByTaskId[task.id] = linkedSetOf(
                refreshTargetDirectory,
                SftpTreeOperationUtils.parentDirectory(task.remotePath)
            )
        }
    }

    private fun createUploadTask(item: UploadPlanItem): TransferTask {
        return TransferTask(
            type = TransferTask.TransferType.UPLOAD,
            localFile = item.localFile,
            remotePath = item.remotePath,
            config = config,
            fileSize = item.localFile.length()
        )
    }

    private fun getPrimarySelectedFileNode(): FileNode? {
        return (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode
    }

    private fun getSelectedFileNodes(): List<FileNode> {
        return tree.selectionPaths.orEmpty()
            .mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode }
    }

    private fun getEffectiveSelectedFileNodes(): List<FileNode> {
        val nodesByPath = getSelectedFileNodes().associateBy { it.path }
        return SftpTreeOperationUtils.deduplicatePaths(nodesByPath.keys.toList())
            .mapNotNull { nodesByPath[it] }
            .filterNot { it.path == currentPath }
    }

    private fun findNodeByPath(path: String): DefaultMutableTreeNode? {
        fun search(node: DefaultMutableTreeNode): DefaultMutableTreeNode? {
            val fileNode = node.userObject as? FileNode
            if (fileNode?.path == path) {
                return node
            }
            for (index in 0 until node.childCount) {
                val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                search(child)?.let { return it }
            }
            return null
        }
        return search(rootNode)
    }

    private fun ensureRemoteDirectory(sftp: SftpClient, directory: String) {
        if (directory.isBlank() || directory == "/") {
            return
        }

        val segments = directory.split("/").filter { it.isNotBlank() }
        var current = ""
        segments.forEach { segment ->
            current = "$current/$segment"
            try {
                val attrs = sftp.stat(current)
                if (!attrs.isDirectory) {
                    throw IllegalStateException("$current 不是目录")
                }
            } catch (_: Exception) {
                sftp.mkdir(current)
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
            var fileName = fileNode.name
            var localFile = File(saveDir, fileName)
            
            // 检查文件是否已存在
            if (localFile.exists()) {
                val options = arrayOf("覆盖", "重命名", "取消")
                val result = Messages.showDialog(
                    project,
                    "文件 \"$fileName\" 已存在，请选择操作：",
                    "文件已存在",
                    options,
                    1,  // 默认选择"重命名"
                    Messages.getWarningIcon()
                )
                
                when (result) {
                    0 -> {
                        // 覆盖：直接使用原文件名
                    }
                    1 -> {
                        // 重命名：让用户输入新文件名
                        val newName = Messages.showInputDialog(
                            project,
                            "请输入新的文件名：",
                            "重命名",
                            null,
                            generateNewFileName(saveDir, fileName),
                            null
                        ) ?: return
                        
                        if (newName.isBlank()) return
                        fileName = newName
                        localFile = File(saveDir, fileName)
                        
                        // 再次检查新文件名是否存在
                        if (localFile.exists()) {
                            Messages.showErrorDialog(project, "文件 \"$fileName\" 已存在", "错误")
                            return
                        }
                    }
                    else -> return  // 取消
                }
            }
            
            // 创建下载任务并添加到传输管理器
            val task = TransferTask(
                type = TransferTask.TransferType.DOWNLOAD,
                localFile = localFile,
                remotePath = fileNode.path,
                config = config,
                fileSize = fileNode.size
            )
            TransferTaskManager.addTask(task)
            statusLabel.text = "已添加下载任务: ${fileName}"
        }
    }

    /**
     * 生成新的文件名（添加序号）
     */
    private fun generateNewFileName(dir: File, originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex > 0) originalName.substring(dotIndex) else ""
        
        var counter = 1
        var newName = "${baseName}_$counter$extension"
        while (File(dir, newName).exists()) {
            counter++
            newName = "${baseName}_$counter$extension"
        }
        return newName
    }

    private fun showCreateEntryDialog(targetDirectory: String = currentPath) {
        val dialog = RemoteCreateEntryDialog(project, targetDirectory)
        if (!dialog.showAndGet()) {
            return
        }

        val request = try {
            dialog.request
        } catch (e: IllegalArgumentException) {
            Messages.showErrorDialog(project, e.message ?: "路径无效", "新建失败")
            return
        }

        val typeLabel = if (request.isDirectory) "文件夹" else "文件"
        val confirmMessage = "将在目录 $targetDirectory 下创建$typeLabel：${request.relativePath}${if (request.isDirectory) "/" else ""}\n是否继续？"
        if (Messages.showYesNoDialog(project, confirmMessage, "确认创建", Messages.getQuestionIcon()) != Messages.YES) {
            return
        }

        createEntries(targetDirectory, listOf(request), isBatch = false)
    }

    private fun showBatchCreateDialog(targetDirectory: String = currentPath) {
        val dialog = RemoteBatchCreateDialog(project, targetDirectory)
        if (!dialog.showAndGet()) {
            return
        }

        val requests = try {
            dialog.requests
        } catch (e: IllegalArgumentException) {
            Messages.showErrorDialog(project, e.message ?: "输入无效", "批量创建失败")
            return
        }

        val confirmMessage = buildBatchCreateConfirmMessage(targetDirectory, requests)
        if (Messages.showYesNoDialog(project, confirmMessage, "确认批量创建", Messages.getQuestionIcon()) != Messages.YES) {
            return
        }

        createEntries(targetDirectory, requests, isBatch = true)
    }

    private fun buildBatchCreateConfirmMessage(targetDirectory: String, requests: List<RemoteCreateRequest>): String {
        val fileCount = requests.count { !it.isDirectory }
        val directoryCount = requests.size - fileCount
        val preview = requests.take(10).joinToString("\n") {
            val typeLabel = if (it.isDirectory) "[文件夹]" else "[文件]"
            "$typeLabel ${it.relativePath}${if (it.isDirectory) "/" else ""}"
        }
        val more = if (requests.size > 10) "\n... 还有 ${requests.size - 10} 条" else ""
        return buildString {
            appendLine("目标目录: $targetDirectory")
            appendLine("将创建 ${fileCount} 个文件，${directoryCount} 个文件夹。")
            appendLine()
            append(preview)
            append(more)
            appendLine()
            append("是否继续？")
        }
    }

    private fun createEntries(targetDirectory: String, requests: List<RemoteCreateRequest>, isBatch: Boolean) {
        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                SwingUtilities.invokeLater {
                    statusLabel.text = if (isBatch) "正在批量创建..." else "正在创建..."
                    statusLabel.icon = PluginIcons.Pending
                }

                val created = mutableListOf<RemoteCreateRequest>()
                val skipped = mutableListOf<RemoteCreateRequest>()
                val failures = mutableListOf<String>()

                requests.forEach { request ->
                    runCatching {
                        val remotePath = UploadPathUtils.buildRemotePath(targetDirectory, request.relativePath)
                        createSingleEntry(sftp, remotePath, request, allowSkipExisting = isBatch)
                    }.onSuccess { result ->
                        when (result) {
                            CreateEntryResult.CREATED -> created += request
                            CreateEntryResult.SKIPPED -> skipped += request
                        }
                    }.onFailure { error ->
                        failures += "${request.relativePath}${if (request.isDirectory) "/" else ""}: ${error.message}"
                    }
                }

                SwingUtilities.invokeLater {
                    refreshVisibleDirectory(targetDirectory)
                    when {
                        !isBatch && failures.isEmpty() -> {
                            val typeLabel = if (requests.first().isDirectory) "文件夹" else "文件"
                            statusLabel.text = "已创建$typeLabel"
                            statusLabel.icon = PluginIcons.Success
                        }

                        !isBatch -> {
                            statusLabel.text = "创建失败"
                            statusLabel.icon = PluginIcons.Error
                            Messages.showErrorDialog(project, failures.first(), "创建失败")
                        }

                        else -> {
                            statusLabel.text = "批量创建完成"
                            statusLabel.icon = if (failures.isEmpty()) PluginIcons.Success else PluginIcons.Error
                            Messages.showInfoMessage(project, buildBatchCreateResultMessage(created, skipped, failures), "批量创建结果")
                        }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "创建失败"
                    statusLabel.icon = PluginIcons.Error
                    Messages.showErrorDialog(project, "创建失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun createSingleEntry(
        sftp: SftpClient,
        remotePath: String,
        request: RemoteCreateRequest,
        allowSkipExisting: Boolean
    ): CreateEntryResult {
        val existing = statOrNull(sftp, remotePath)
        if (existing != null) {
            if (allowSkipExisting && request.isDirectory && existing.isDirectory) {
                return CreateEntryResult.SKIPPED
            }
            if (allowSkipExisting && !request.isDirectory && !existing.isDirectory) {
                return CreateEntryResult.SKIPPED
            }
            val existingType = if (existing.isDirectory) "文件夹" else "文件"
            if (allowSkipExisting) {
                throw IllegalStateException("目标已存在且类型不匹配: $remotePath（现有类型: $existingType）")
            }
            throw IllegalStateException("目标已存在: $remotePath（现有类型: $existingType）")
        }

        if (request.isDirectory) {
            ensureRemoteDirectory(sftp, remotePath)
        } else {
            ensureRemoteDirectory(sftp, SftpTreeOperationUtils.parentDirectory(remotePath))
            sftp.write(remotePath).use { }
        }
        return CreateEntryResult.CREATED
    }

    private fun statOrNull(sftp: SftpClient, path: String): SftpClient.Attributes? {
        return try {
            sftp.stat(path)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildBatchCreateResultMessage(
        created: List<RemoteCreateRequest>,
        skipped: List<RemoteCreateRequest>,
        failures: List<String>
    ): String {
        return buildString {
            appendLine("成功 ${created.size} 项，跳过 ${skipped.size} 项，失败 ${failures.size} 项。")
            if (skipped.isNotEmpty()) {
                appendLine()
                appendLine("跳过：")
                skipped.take(10).forEach {
                    appendLine("- ${it.relativePath}${if (it.isDirectory) "/" else ""}")
                }
                if (skipped.size > 10) {
                    appendLine("... 还有 ${skipped.size - 10} 项")
                }
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("失败：")
                failures.take(10).forEach { appendLine("- $it") }
                if (failures.size > 10) {
                    appendLine("... 还有 ${failures.size - 10} 项")
                }
            }
        }.trim()
    }

    private fun deleteSelected() {
        val selectedNodes = getEffectiveSelectedFileNodes()
        if (selectedNodes.isEmpty()) return
        val confirmation = RemoteRiskOperationUtils.buildDeleteConfirmation(
            selectedNodes.map { RemotePathItem(it.path, it.isDirectory) }
        )
        if (Messages.showYesNoDialog(project, confirmation.message, confirmation.title, Messages.getWarningIcon()) != Messages.YES) {
            return
        }
        val isBatch = selectedNodes.size > 1

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                SwingUtilities.invokeLater {
                    statusLabel.text = if (isBatch) "正在批量删除..." else "正在删除..."
                    statusLabel.icon = PluginIcons.Pending
                }

                selectedNodes.forEach { fileNode ->
                    deleteRemoteNodeRecursively(sftp, fileNode.path, fileNode.isDirectory)
                }

                SwingUtilities.invokeLater {
                    statusLabel.text = if (isBatch) "已批量删除 ${selectedNodes.size} 项" else "已删除"
                    statusLabel.icon = PluginIcons.Success
                    refresh()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "删除失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val selectedNodes = getEffectiveSelectedFileNodes()
        val primaryNode = getPrimarySelectedFileNode()
        val isBatch = selectedNodes.size > 1

        JPopupMenu().apply {
            if (!isBatch && primaryNode?.isDirectory == true) {
                add(JMenuItem("打开", PluginIcons.Open).apply {
                    addActionListener { navigateTo(primaryNode.path) }
                })
                add(JMenuItem("新建...", PluginIcons.NewFile).apply {
                    addActionListener { showCreateEntryDialog(primaryNode.path) }
                })
                add(JMenuItem("批量创建...", PluginIcons.NewFolder).apply {
                    addActionListener { showBatchCreateDialog(primaryNode.path) }
                })
                addSeparator()
                add(JMenuItem("上传到此目录", PluginIcons.Upload).apply {
                    addActionListener { uploadToDirectory(primaryNode.path) }
                })
                add(JMenuItem("批量上传到此目录", PluginIcons.BatchUpload).apply {
                    addActionListener { batchUploadFiles(primaryNode.path) }
                })
                addSeparator()
            }
            if (!isBatch && primaryNode != null && !primaryNode.isDirectory) {
                add(JMenuItem("编辑", PluginIcons.Edit).apply {
                    addActionListener { openFileInEditor(primaryNode) }
                })
                add(JMenuItem("下载", PluginIcons.Download).apply {
                    addActionListener { downloadSelected() }
                })
            }
            if (!isBatch && primaryNode != null) {
                add(JMenuItem("重命名", PluginIcons.Rename).apply {
                    addActionListener { renameFile(primaryNode) }
                })
                addSeparator()
                add(JMenuItem("复制路径", PluginIcons.Copy).apply {
                    addActionListener {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(primaryNode.path), null)
                        statusLabel.text = "已复制路径"
                    }
                })
                add(JMenuItem("属性", PluginIcons.Properties).apply {
                    addActionListener { showProperties(primaryNode) }
                })
            }
            if (selectedNodes.isNotEmpty()) {
                if (componentCount > 0) {
                    addSeparator()
                }
                add(JMenuItem(if (isBatch) "批量删除" else "删除", if (isBatch) PluginIcons.BatchDelete else PluginIcons.Delete).apply {
                    addActionListener { deleteSelected() }
                })
            }
        }.show(e.component, e.x, e.y)
    }

    private fun uploadToDirectory(targetDir: String) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            queueUploadPlan(SftpTreeOperationUtils.buildUploadPlan(listOf(File(vf.path)), targetDir), targetDir)
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

    private fun deleteRemoteNodeRecursively(sftp: SftpClient, path: String, isDirectory: Boolean) {
        if (isDirectory) {
            sftp.readDir(path)
                .filter { it.filename != "." && it.filename != ".." }
                .forEach { entry ->
                    val childPath = UploadPathUtils.buildRemotePath(path, entry.filename)
                    deleteRemoteNodeRecursively(sftp, childPath, entry.attributes.isDirectory)
                }
            sftp.rmdir(path)
            return
        }

        sftp.remove(path)
        remoteFileEditorService.deleteLocalCache(path)
    }

    private fun moveSelectedNodes(targetDirectory: String, sourcePaths: List<String>? = null) {
        val selectedNodes = if (sourcePaths == null) {
            getEffectiveSelectedFileNodes()
        } else {
            sourcePaths.mapNotNull { path -> (findNodeByPath(path)?.userObject as? FileNode) }
        }
        if (selectedNodes.isEmpty()) {
            return
        }

        val conflicts = selectedNodes.filter {
            it.isDirectory && SftpTreeOperationUtils.isMoveIntoSelf(it.path, targetDirectory)
        }
        if (conflicts.isNotEmpty()) {
            Messages.showErrorDialog(project, "不能将目录移动到它自己或其子目录中", "移动失败")
            return
        }

        val moveCandidates = selectedNodes.filter { node ->
            UploadPathUtils.buildRemotePath(targetDirectory, node.name) != node.path
        }
        if (moveCandidates.isEmpty()) {
            return
        }

        val confirmation = RemoteRiskOperationUtils.buildMoveConfirmation(
            items = moveCandidates.map { RemotePathItem(it.path, it.isDirectory) },
            targetDirectory = targetDirectory
        )
        if (Messages.showYesNoDialog(project, confirmation.message, confirmation.title, Messages.getWarningIcon()) != Messages.YES) {
            return
        }

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val movePairs = moveCandidates.mapNotNull { node ->
                    val destination = UploadPathUtils.buildRemotePath(targetDirectory, node.name)
                    if (destination == node.path) null else node.path to destination
                }

                movePairs.forEach { (_, destination) ->
                    try {
                        sftp.stat(destination)
                        throw IllegalStateException("目标已存在: $destination")
                    } catch (conflict: IllegalStateException) {
                        throw conflict
                    } catch (_: Exception) {
                    }
                }

                movePairs.forEach { (source, destination) ->
                    ensureRemoteDirectory(sftp, destination.substringBeforeLast("/").ifEmpty { "/" })
                    sftp.rename(source, destination)
                }

                SwingUtilities.invokeLater {
                    statusLabel.text = if (movePairs.size > 1) "已移动 ${movePairs.size} 项" else "移动成功"
                    statusLabel.icon = PluginIcons.Success
                    refresh()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "移动失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun resolveDropDirectory(treePath: TreePath?): String {
        val fileNode = (treePath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode
        return when {
            fileNode == null -> currentPath
            fileNode.isDirectory -> fileNode.path
            else -> fileNode.path.substringBeforeLast("/").ifEmpty { "/" }
        }
    }

    private fun scheduleDirectoryRefreshes(paths: Collection<String>) {
        if (paths.isEmpty()) {
            return
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { scheduleDirectoryRefreshes(paths) }
            return
        }
        val visibleDirectories = collectVisibleDirectoryPaths()
        paths.asSequence()
            .filter { it.isNotBlank() }
            .map { SftpTreeOperationUtils.resolveRefreshDirectory(it, currentPath, visibleDirectories) }
            .forEach { pendingRefreshDirectories.add(it) }
        deferredRefreshTimer.restart()
    }

    private fun flushPendingDirectoryRefreshes() {
        val directories = pendingRefreshDirectories.toList()
            .sortedBy { it.length }
        pendingRefreshDirectories.clear()
        directories.forEach { refreshVisibleDirectory(it) }
    }

    private fun refreshVisibleDirectory(path: String) {
        val visibleDirectories = collectVisibleDirectoryPaths()
        val refreshPath = SftpTreeOperationUtils.resolveRefreshDirectory(path, currentPath, visibleDirectories)
        if (refreshPath == currentPath) {
            loadRootDirectory(currentPath)
            return
        }
        val node = findNodeByPath(refreshPath)
        if (node != null) {
            loadDirectory(refreshPath, node)
        } else {
            loadRootDirectory(currentPath)
        }
    }

    private fun collectVisibleDirectoryPaths(): Set<String> {
        val directories = linkedSetOf<String>()

        fun collect(node: DefaultMutableTreeNode) {
            val fileNode = node.userObject as? FileNode
            if (fileNode?.isDirectory == true) {
                directories.add(fileNode.path)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                collect(child)
            }
        }

        collect(rootNode)
        return directories
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
        statusLabel.icon = PluginIcons.Pending
        
        remoteFileEditorService.openRemoteFile(fileNode.path) { error ->
            statusLabel.text = error
            statusLabel.icon = PluginIcons.Error
            Messages.showErrorDialog(project, error, "打开文件失败")
        }
        
        // 延迟恢复状态
        executor.submit {
            Thread.sleep(1000)
            SwingUtilities.invokeLater {
                if (statusLabel.text.startsWith("正在打开")) {
                    statusLabel.text = "已连接"
                    statusLabel.icon = PluginIcons.Success
                }
            }
        }
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / 1024 / 1024} MB"
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private inner class FileTreeTransferHandler : TransferHandler() {

        override fun getSourceActions(c: JComponent): Int = DnDConstants.ACTION_COPY_OR_MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val selectedPaths = getEffectiveSelectedFileNodes().map { it.path }
            if (selectedPaths.isEmpty()) {
                return null
            }
            return object : Transferable {
                private val data = RemoteTreeTransferData(selectedPaths)

                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(remoteNodeFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == remoteNodeFlavor

                override fun getTransferData(flavor: DataFlavor): Any {
                    if (!isDataFlavorSupported(flavor)) {
                        throw UnsupportedOperationException("Unsupported flavor: $flavor")
                    }
                    return data
                }
            }
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) {
                return false
            }
            val targetDirectory = resolveDropDirectory((support.dropLocation as? JTree.DropLocation)?.path)
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                support.dropAction = DnDConstants.ACTION_COPY
                return targetDirectory.isNotBlank()
            }
            if (!support.isDataFlavorSupported(remoteNodeFlavor)) {
                return false
            }
            return try {
                val data = support.transferable.getTransferData(remoteNodeFlavor) as RemoteTreeTransferData
                val nodesByPath = getEffectiveSelectedFileNodes().associateBy { it.path }
                val selectedNodes = data.paths.mapNotNull { nodesByPath[it] }
                selectedNodes.none { it.isDirectory && SftpTreeOperationUtils.isMoveIntoSelf(it.path, targetDirectory) }
            } catch (_: Exception) {
                false
            }
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) {
                return false
            }

            val targetDirectory = resolveDropDirectory((support.dropLocation as? JTree.DropLocation)?.path)
            return try {
                when {
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                        @Suppress("UNCHECKED_CAST")
                        val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        confirmAndQueueDragUpload(SftpTreeOperationUtils.buildUploadPlan(files, targetDirectory), targetDirectory)
                        true
                    }

                    support.isDataFlavorSupported(remoteNodeFlavor) -> {
                        val data = support.transferable.getTransferData(remoteNodeFlavor) as RemoteTreeTransferData
                        moveSelectedNodes(targetDirectory, data.paths)
                        true
                    }

                    else -> false
                }
            } catch (e: Exception) {
                Messages.showErrorDialog(project, "拖拽操作失败: ${e.message}", "错误")
                false
            }
        }
    }

    private fun confirmAndQueueDragUpload(plan: UploadPlan, targetDirectory: String) {
        if (plan.directories.isEmpty() && plan.files.isEmpty()) {
            return
        }

        executor.submit {
            try {
                val sftp = connectionManager.getSftpClient()
                val existingEntries = collectExistingUploadEntries(sftp, plan)
                val analysis = RemoteRiskOperationUtils.analyzeUploadConflicts(plan, existingEntries)

                SwingUtilities.invokeLater {
                    when (showUploadConfirmationDialog(targetDirectory, analysis)) {
                        UploadConfirmationDecision.CANCEL -> Unit
                        UploadConfirmationDecision.OVERWRITE -> queueUploadPlan(plan, targetDirectory)
                        UploadConfirmationDecision.SKIP_CONFLICTS -> {
                            val filteredPlan = filterUploadPlanForSkip(plan, analysis)
                            if (filteredPlan.directories.isEmpty() && filteredPlan.files.isEmpty()) {
                                Messages.showInfoMessage(project, "所有待上传项都与远程目标冲突，未添加上传任务。", "已跳过冲突项")
                            } else {
                                queueUploadPlan(filteredPlan, targetDirectory)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(project, "上传前检查失败: ${e.message}", "错误")
                }
            }
        }
    }

    private fun collectExistingUploadEntries(sftp: SftpClient, plan: UploadPlan): Map<String, Boolean> {
        val existingEntries = linkedMapOf<String, Boolean>()
        plan.directories.sorted().forEach { remotePath ->
            statOrNull(sftp, remotePath)?.let { existingEntries[remotePath] = it.isDirectory }
        }
        plan.files.map { it.remotePath }.sorted().forEach { remotePath ->
            statOrNull(sftp, remotePath)?.let { existingEntries[remotePath] = it.isDirectory }
        }
        return existingEntries
    }

    private fun showUploadConfirmationDialog(
        targetDirectory: String,
        analysis: com.lhstack.ssh.util.UploadConflictAnalysis
    ): UploadConfirmationDecision {
        val confirmation = RemoteRiskOperationUtils.buildUploadConfirmation(targetDirectory, analysis)
        return if (analysis.conflicts.isEmpty()) {
            val result = Messages.showYesNoDialog(project, "${confirmation.message}\n\n是否继续？", confirmation.title, Messages.getQuestionIcon())
            if (result == Messages.YES) UploadConfirmationDecision.OVERWRITE else UploadConfirmationDecision.CANCEL
        } else {
            when (
                Messages.showDialog(
                    project,
                    confirmation.message,
                    confirmation.title,
                    arrayOf("覆盖上传", "跳过冲突项", "取消"),
                    1,
                    Messages.getWarningIcon()
                )
            ) {
                0 -> UploadConfirmationDecision.OVERWRITE
                1 -> UploadConfirmationDecision.SKIP_CONFLICTS
                else -> UploadConfirmationDecision.CANCEL
            }
        }
    }

    private fun filterUploadPlanForSkip(
        plan: UploadPlan,
        analysis: com.lhstack.ssh.util.UploadConflictAnalysis
    ): UploadPlan {
        val conflictsByPath = analysis.conflicts.associateBy { it.remotePath }
        val directories = plan.directories.filterTo(linkedSetOf()) { remotePath ->
            val conflict = conflictsByPath[remotePath] ?: return@filterTo true
            conflict.existingDirectory
        }
        val files = plan.files.filter { item -> item.remotePath !in conflictsByPath }
        return UploadPlan(directories = directories, files = files)
    }

    override fun dispose() {
        TransferTaskManager.removeListener(uploadTaskListener)
        deferredRefreshTimer.stop()
        executor.shutdownNow()
        remoteFileEditorService.dispose()
        connectionManager.close()
    }
}

data class FileNode(val path: String, val isDirectory: Boolean, val size: Long, val modifyTime: Long, val permissions: String) {
    val name: String get() = path.substringAfterLast("/").ifEmpty { "/" }
}

private data class RemoteTreeTransferData(val paths: List<String>)

private enum class CreateEntryResult {
    CREATED,
    SKIPPED
}

private enum class UploadConfirmationDecision {
    OVERWRITE,
    SKIP_CONFLICTS,
    CANCEL
}

class FileTreeRenderer : ColoredTreeCellRenderer() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is FileNode -> {
                icon = if (obj.isDirectory) PluginIcons.Folder else getFileIcon(obj.name)
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
                icon = PluginIcons.Pending
                append(obj, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private fun getFileIcon(name: String): Icon {
        val ext = name.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "java", "kt", "scala", "xml", "html", "htm" -> PluginIcons.FileCode
            "json", "yaml", "yml" -> PluginIcons.FileData
            "sh", "bash" -> PluginIcons.FileScript
            "txt", "md", "log" -> PluginIcons.FileText
            "jar", "war", "zip", "tar", "gz" -> PluginIcons.FileArchive
            "png", "jpg", "jpeg", "gif", "svg" -> PluginIcons.FileImage
            else -> PluginIcons.FileGeneric
        }
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }
}
