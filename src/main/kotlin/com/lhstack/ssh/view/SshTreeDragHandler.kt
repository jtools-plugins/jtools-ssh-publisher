package com.lhstack.ssh.view

import com.intellij.util.ui.UIUtil
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.SshGroup
import com.lhstack.ssh.service.SshConfigService
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.*
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * SSH 连接树拖拽处理器（通用版）
 *
 * 支持 MainView SSH 树和 AnsibleRunnerPanel 树。
 * 通过 [configExtractor] / [groupIdExtractor] 适配不同节点类型。
 *
 * 拖拽逻辑：
 * - 拖到分组节点 → 移动到该分组末尾
 * - 拖到 SshConfig 节点 → 插到该节点前/后（自动切换分组）
 * - 拖影：显示被拖项的名称标签
 */
class SshTreeDragHandler(
    private val tree: JTree,
    private val onDropped: () -> Unit,
    /** 从节点 userObject 提取 SshConfig，null 表示非配置节点 */
    private val configExtractor: (Any?) -> SshConfig? = { it as? SshConfig },
    /** 从节点 userObject 提取目标 groupId，null 表示非分组节点 */
    private val groupIdExtractor: (Any?) -> String? = {
        when (it) {
            is SshGroup -> it.id
            is String   -> ""       // "默认"分组
            else        -> null
        }
    }
) {

    /** 拖拽目标行，-1 表示无 */
    var dropTargetRow: Int = -1
        private set
    /** true = 插入到目标行上方 */
    var dropInsertAbove: Boolean = true
        private set

    init {
        setupDragSource()
        setupDropTarget()
    }

    // -------------------------------------------------------------------------
    // 拖动源：DragGestureRecognizer + 自定义拖影
    // -------------------------------------------------------------------------

    private fun setupDragSource() {
        DragSource.getDefaultDragSource().createDefaultDragGestureRecognizer(
            tree, DnDConstants.ACTION_MOVE
        ) { dge ->
            val paths = tree.selectionPaths ?: return@createDefaultDragGestureRecognizer
            val configs = paths.mapNotNull { path ->
                configExtractor((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject)
            }
            if (configs.isEmpty()) return@createDefaultDragGestureRecognizer

            val ids = configs.map { it.id }
            val (img, offset) = buildDragImage(configs)

            try {
                dge.startDrag(
                    DragSource.DefaultMoveDrop,
                    img, offset,
                    SshConfigTransferable(ids),
                    object : DragSourceAdapter() {}
                )
            } catch (_: InvalidDnDOperationException) { /* 已有拖单进行 */ }
        }
    }

    /**
     * 构建拖影图像：显示被拖配置的名称，多选时显示数量
     */
    private fun buildDragImage(configs: List<SshConfig>): Pair<BufferedImage, Point> {
        val text = if (configs.size == 1) " ${configs[0].name} " else " ${configs.size} 个连接 "
        val label = JLabel(text).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIUtil.getLabelForeground().darker(), 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
            background = UIUtil.getPanelBackground()
            foreground = UIUtil.getLabelForeground()
            isOpaque = true
            font = UIUtil.getLabelFont()
        }
        val size = label.preferredSize
        label.setSize(size)
        val img = UIUtil.createImage(tree, size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f)
        label.paint(g)
        g.dispose()
        return Pair(img, Point(size.width / 2, size.height / 2))
    }

    // -------------------------------------------------------------------------
    // 拖拽目标：全部逻辑在 drop() 里完成
    // -------------------------------------------------------------------------

    private fun setupDropTarget() {
        DropTarget(tree, DnDConstants.ACTION_MOVE, object : DropTargetAdapter() {

            override fun dragOver(dtde: DropTargetDragEvent) {
                val pt = dtde.location
                val row = tree.getRowForLocation(pt.x, pt.y)
                val bounds = if (row >= 0) tree.getRowBounds(row) else null
                val above = bounds == null || (pt.y - bounds.y) < bounds.height / 2

                val changed = row != dropTargetRow || above != dropInsertAbove
                dropTargetRow = row
                dropInsertAbove = above
                if (changed) tree.repaint()

                if (row >= 0 && isValidDropRow(row)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragExit(dte: DropTargetEvent) {
                dropTargetRow = -1
                tree.repaint()
            }

            @Suppress("UNCHECKED_CAST")
            override fun drop(dtde: DropTargetDropEvent) {
                val savedRow   = dropTargetRow
                val savedAbove = dropInsertAbove
                dropTargetRow  = -1
                tree.repaint()

                dtde.acceptDrop(DnDConstants.ACTION_MOVE)
                try {
                    val ids = dtde.transferable
                        .getTransferData(SshConfigTransferable.FLAVOR) as? List<String>
                        ?: run { dtde.dropComplete(false); return }

                    val dragged = ids.mapNotNull { SshConfigService.getConfigById(it) }
                    if (dragged.isEmpty()) { dtde.dropComplete(false); return }

                    val targetNode = tree.getPathForRow(savedRow)
                        ?.lastPathComponent as? DefaultMutableTreeNode
                        ?: run { dtde.dropComplete(false); return }

                    val obj = targetNode.userObject
                    val targetGroupId = groupIdExtractor(obj)
                    val targetConfig  = configExtractor(obj)

                    when {
                        targetGroupId != null -> moveToGroupTail(dragged, targetGroupId)
                        targetConfig  != null -> insertAroundConfig(dragged, targetConfig, savedAbove)
                        else -> { dtde.dropComplete(false); return }
                    }

                    dtde.dropComplete(true)
                    onDropped()
                } catch (_: Exception) {
                    dtde.dropComplete(false)
                }
            }
        })
    }

    private fun isValidDropRow(row: Int): Boolean {
        val obj = (tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
        return groupIdExtractor(obj) != null || configExtractor(obj) != null
    }

    // -------------------------------------------------------------------------
    // 移动逻辑
    // -------------------------------------------------------------------------

    /** 移动到指定分组末尾 */
    private fun moveToGroupTail(dragged: List<SshConfig>, newGroupId: String) {
        val draggedIds = dragged.map { it.id }.toSet()
        val existing = SshConfigService.getConfigs()
            .filter { it.groupId == newGroupId && it.id !in draggedIds }
            .sortedBy { it.sortOrder }
        val startOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        dragged.forEachIndexed { i, config ->
            SshConfigService.updateConfig(config.copy(groupId = newGroupId, sortOrder = startOrder + i))
        }
    }

    /** 插到目标连接节点的前面或后面（自动切换到目标分组） */
    private fun insertAroundConfig(
        dragged: List<SshConfig>,
        target: SshConfig,
        insertAbove: Boolean
    ) {
        val targetGroupId = target.groupId
        val draggedIds = dragged.map { it.id }.toSet()

        // 目标分组内剩余项（排除被拖的）
        val base = SshConfigService.getConfigs()
            .filter { it.groupId == targetGroupId && it.id !in draggedIds }
            .sortedBy { it.sortOrder }
            .toMutableList()

        val targetIdx = base.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        val insertAt  = if (insertAbove) targetIdx else (targetIdx + 1).coerceAtMost(base.size)

        // 切换到目标分组
        val toInsert = dragged.map { it.copy(groupId = targetGroupId) }
        base.addAll(insertAt, toInsert)

        base.forEachIndexed { i, config ->
            SshConfigService.updateConfig(config.copy(sortOrder = i))
        }
    }

    // -------------------------------------------------------------------------

    class SshConfigTransferable(private val ids: List<String>) : Transferable {
        companion object {
            val FLAVOR: DataFlavor = DataFlavor(List::class.java, "SshConfigIds")
        }
        override fun getTransferDataFlavors() = arrayOf(FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == FLAVOR
        override fun getTransferData(flavor: DataFlavor): Any = ids
    }
}
