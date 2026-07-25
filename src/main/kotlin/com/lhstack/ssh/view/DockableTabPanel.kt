package com.lhstack.ssh.view

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.awt.RelativePoint
import com.lhstack.ssh.PluginIcons
import com.lhstack.ssh.util.AnActionFactory
import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.border.Border

/**
 * 支持拖拽排序和分屏的Tab面板
 *
 * 拖拽交互（参考 IDEA JBTabs 的拖出体验，适配无限层级分屏）：
 * - 按住某个 Tab 拖动，达到拖动阈值后，源容器立即移除该 Tab，鼠标跟随一张半透明幽灵标签；
 * - 拖动过程中在鼠标所在容器插入一个高亮“占位标签”，标示落点位置，随鼠标实时更新；
 * - 松手时把真实 Tab 落到占位所在位置；拖到任何容器之外则回退到源容器原位置。
 * 同容器排序与跨容器移动使用同一套逻辑。
 */
class DockableTabPanel(private val parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {

    private var rootContainer: TabContainer
    private var activeContainer: TabContainer? = null

    // 全局唯一的拖拽会话；同一时刻只允许一个 Tab 被拖拽
    private var dragSession: DragSession? = null

    // 通过全局焦点事件确定活动容器，避免给每个子组件重复挂监听导致的泄漏与脏引用
    private val focusListener = AWTEventListener { event ->
        if (event is FocusEvent && event.id == FocusEvent.FOCUS_GAINED) {
            val container = findEnclosingContainer(event.component)
            if (container != null) {
                setActiveContainer(container)
            }
        }
    }

    init {
        Disposer.register(parentDisposable, this)
        rootContainer = TabContainer(this)
        activeContainer = rootContainer
        add(rootContainer, BorderLayout.CENTER)
        Toolkit.getDefaultToolkit().addAWTEventListener(focusListener, AWTEvent.FOCUS_EVENT_MASK)
    }

    /**
     * 从任意组件向上查找其所属、且属于本面板的叶子 TabContainer
     */
    private fun findEnclosingContainer(component: Component?): TabContainer? {
        var current: Component? = component
        while (current != null) {
            if (current is TabContainer && current.belongsTo(this) && !current.isSplit()) {
                return current
            }
            current = current.parent
        }
        return null
    }

    fun addTab(title: String, icon: Icon, component: JComponent, tooltip: String) {
        // 添加到当前活动的容器，确保是叶子容器（未分屏的）
        val target = findLeafContainer(activeContainer)
            ?: findFirstLeafContainer(rootContainer)
            ?: rootContainer
        target.addTab(TabInfo(title, icon, component, tooltip))
    }

    /**
     * 如果容器已分屏，返回其第一个叶子容器；否则返回自身
     */
    private fun findLeafContainer(container: TabContainer?): TabContainer? {
        container ?: return null
        return if (container.isSplit()) {
            findLeafContainer(container.getFirstChild()) ?: findLeafContainer(container.getSecondChild())
        } else {
            container
        }
    }

    private fun findFirstLeafContainer(container: TabContainer): TabContainer? {
        return if (container.isSplit()) {
            findFirstLeafContainer(container.getFirstChild()!!) ?: findFirstLeafContainer(container.getSecondChild()!!)
        } else {
            container
        }
    }

    fun getTabCount(): Int = rootContainer.getTotalTabCount()

    override fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(focusListener)
        rootContainer.dispose()
    }

    internal fun setActiveContainer(container: TabContainer?) {
        // 若传入的是分屏容器，下沉到其第一个叶子，避免 activeContainer 指向不可承载 Tab 的容器
        activeContainer = if (container == null) null else findLeafContainer(container)
    }

    internal fun replaceRoot(newRoot: TabContainer) {
        remove(rootContainer)
        rootContainer = newRoot
        newRoot.setParentPanel(this)
        add(rootContainer, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    internal fun getRootContainer() = rootContainer

    /**
     * 查找屏幕坐标下的叶子容器
     */
    internal fun findContainerAt(screenPoint: Point): TabContainer? {
        return findContainerAtRecursive(rootContainer, screenPoint)
    }

    private fun findContainerAtRecursive(container: TabContainer, screenPoint: Point): TabContainer? {
        if (!container.isShowing) return null

        val containerBounds = Rectangle(container.locationOnScreen, container.size)
        if (!containerBounds.contains(screenPoint)) return null

        return if (container.isSplit()) {
            findContainerAtRecursive(container.getFirstChild()!!, screenPoint)
                ?: findContainerAtRecursive(container.getSecondChild()!!, screenPoint)
        } else {
            container
        }
    }

    // ======== 拖拽会话 ========

    /**
     * 开始一次拖拽：源容器立即移除该 Tab，创建跟随鼠标的幽灵标签。
     */
    internal fun beginDrag(tab: TabInfo, source: TabContainer, screenPoint: Point) {
        if (dragSession != null) return
        val ghost = DragGhost(tab)
        val session = DragSession(tab, source, ghost)
        dragSession = session
        // 拖拽进行中只分离不合并：合并会卸载正在监听鼠标拖拽的源 tabbedPane，中断拖拽。
        // 源容器即使拖空也保留展示，等松手落位后再收敛。
        source.detachTab(tab)
        showGhost(ghost, screenPoint)
        updateDrag(screenPoint)
    }

    /**
     * 拖拽进行中：跟随鼠标移动幽灵标签，并在鼠标所在容器更新落点占位标签。
     */
    internal fun updateDrag(screenPoint: Point) {
        val session = dragSession ?: return
        moveGhost(session.ghost, screenPoint)
        val target = findContainerAt(screenPoint)
        if (target == null) {
            session.clearPlaceholder()
            return
        }
        // 鼠标仍停在当前占位标签范围内：落点即占位当前位置，保持不动直接返回。
        // 否则占位插入推开真实标签后会与按坐标重算的落点反复错位，形成“插入-推移-重算-翻转”的闪烁环。
        if (session.placeholderPane === target && target.isPointerOverPlaceholder(screenPoint)) {
            return
        }
        val index = target.dropIndexAt(screenPoint)
        session.movePlaceholderTo(target, index)
    }

    /**
     * 结束拖拽：把真实 Tab 落到占位位置；无有效落点则回退到源容器原位置。
     */
    internal fun finishDrag(screenPoint: Point) {
        val session = dragSession ?: return
        dragSession = null
        hideGhost(session.ghost)

        val placeholderPane = session.placeholderPane
        val target: TabContainer
        val index: Int
        if (placeholderPane != null) {
            index = placeholderPane.placeholderIndex().coerceAtLeast(0)
            session.clearPlaceholder()
            target = placeholderPane
        } else {
            session.clearPlaceholder()
            val hovered = findContainerAt(screenPoint)
            if (hovered != null) {
                target = hovered
                index = hovered.dropIndexAt(screenPoint)
            } else {
                // 拖到面板之外：回退到源容器原位置
                target = session.source
                index = session.sourceIndex
            }
        }
        target.insertRealTab(session.tab, index, highlight = target !== session.source)
        setActiveContainer(target)
        // 落位完成后再收敛：若源容器已拖空（且不是目标容器），合并回其兄弟，保持分屏结构收敛。
        if (target !== session.source) {
            session.source.tryMergeAfterDetach()
        }
    }

    /**
     * 取消拖拽（异常兜底）：把 Tab 放回源容器原位置。
     */
    internal fun cancelDrag() {
        val session = dragSession ?: return
        dragSession = null
        hideGhost(session.ghost)
        session.clearPlaceholder()
        session.source.insertRealTab(session.tab, session.sourceIndex, highlight = false)
    }

    internal fun isDragging() = dragSession != null

    private fun showGhost(ghost: DragGhost, screenPoint: Point) {
        val layeredPane = SwingUtilities.getRootPane(this)?.layeredPane ?: return
        layeredPane.add(ghost, JLayeredPane.DRAG_LAYER)
        moveGhost(ghost, screenPoint)
        ghost.isVisible = true
    }

    private fun moveGhost(ghost: DragGhost, screenPoint: Point) {
        val layeredPane = SwingUtilities.getRootPane(this)?.layeredPane ?: return
        val local = Point(screenPoint).apply { SwingUtilities.convertPointFromScreen(this, layeredPane) }
        val size = ghost.preferredSize
        ghost.setBounds(local.x + 12, local.y + 12, size.width, size.height)
        layeredPane.repaint()
    }

    private fun hideGhost(ghost: DragGhost) {
        val layeredPane = SwingUtilities.getRootPane(this)?.layeredPane ?: return
        val bounds = ghost.bounds
        layeredPane.remove(ghost)
        layeredPane.repaint(bounds)
    }

    /**
     * 一次拖拽会话的状态。
     */
    private class DragSession(
        val tab: TabInfo,
        val source: TabContainer,
        val ghost: DragGhost
    ) {
        // 源容器中该 Tab 的原索引，用于拖到无效位置时回退
        val sourceIndex: Int = source.indexOfTab(tab).coerceAtLeast(0)

        // 当前占位标签所在容器；null 表示当前鼠标不在任何容器上
        var placeholderPane: TabContainer? = null

        fun movePlaceholderTo(target: TabContainer, index: Int) {
            val current = placeholderPane
            if (current === target && target.placeholderIndex() == index) return
            current?.removePlaceholder()
            target.showPlaceholder(tab, index)
            placeholderPane = target
        }

        fun clearPlaceholder() {
            placeholderPane?.removePlaceholder()
            placeholderPane = null
        }
    }

    /**
     * 跟随鼠标的拖拽幽灵标签：圆角卡片 + 柔和阴影 + 图标 + 标题，整体半透明。
     */
    private class DragGhost(tab: TabInfo) : JPanel(BorderLayout(6, 0)) {
        init {
            isOpaque = false
            // 阴影留白 + 内容内边距
            border = BorderFactory.createEmptyBorder(
                SHADOW_PAD + 4, SHADOW_PAD + 10, SHADOW_PAD + 5, SHADOW_PAD + 10
            )
            add(JLabel(tab.icon), BorderLayout.WEST)
            add(JLabel(tab.title).apply {
                foreground = UIManager.getColor("Label.foreground")
            }, BorderLayout.CENTER)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = 10
            val cardX = SHADOW_PAD
            val cardY = SHADOW_PAD
            val cardW = width - SHADOW_PAD * 2
            val cardH = height - SHADOW_PAD * 2

            // 柔和阴影（多层递减透明度）
            for (i in SHADOW_PAD downTo 1) {
                val alpha = (0.05f * (SHADOW_PAD - i + 1)).coerceAtMost(0.18f)
                g2.color = Color(0f, 0f, 0f, alpha)
                g2.fillRoundRect(cardX - i + 2, cardY - i + 3, cardW + i * 2 - 4, cardH + i * 2 - 4, arc + i, arc + i)
            }

            // 卡片背景（略微不透明）
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f)
            g2.color = UIManager.getColor("Panel.background") ?: background
            g2.fillRoundRect(cardX, cardY, cardW, cardH, arc, arc)

            // 主题色描边
            g2.composite = AlphaComposite.SrcOver
            g2.color = UIManager.getColor("Component.focusColor") ?: ACCENT
            g2.stroke = BasicStroke(1.2f)
            g2.drawRoundRect(cardX, cardY, cardW - 1, cardH - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }

        companion object {
            private const val SHADOW_PAD = 5
            private val ACCENT = Color(0x35, 0x74, 0xF0)
        }
    }

    data class TabInfo(val title: String, val icon: Icon, val component: JComponent, val tooltip: String)

    class TabContainer(private var panel: DockableTabPanel?) : JPanel(BorderLayout()), Disposable {

        private val tabbedPane = JTabbedPane(JTabbedPane.TOP)
        private val tabs = mutableListOf<TabInfo>()
        private var splitPane: JBSplitter? = null
        private var firstChild: TabContainer? = null
        private var secondChild: TabContainer? = null
        private var parentContainer: TabContainer? = null

        // 按下时记录的候选拖拽信息；越过阈值后才真正进入拖拽
        private var pressIndex = -1
        private var pressPoint: Point? = null

        // 落点占位标签：拖拽过程中插入的临时高亮标签，标示 Tab 将落到的位置
        private var placeholderIndex = -1
        private var placeholderComponent: JComponent? = null

        // 拖拽目标容器高亮
        private var isDropTarget = false
        private val dropHighlightBorder = BorderFactory.createLineBorder(UIManager.getColor("Component.focusColor") ?: Color.BLUE, 2)
        // 记录 tabbedPane 初始边框，拖拽高亮结束后恢复，避免写死 null 抹掉默认外观
        private val normalBorder: Border? = tabbedPane.border

        init {
            add(tabbedPane, BorderLayout.CENTER)
            setupDrag()
            setupTabEvents()
        }

        fun isSplit() = splitPane != null
        fun getFirstChild() = firstChild
        fun getSecondChild() = secondChild
        fun setParentPanel(p: DockableTabPanel) { panel = p }
        internal fun belongsTo(owner: DockableTabPanel) = panel === owner

        internal fun indexOfTab(tab: TabInfo) = tabs.indexOf(tab)

        // ======== 拖拽输入 ========

        private fun setupDrag() {
            tabbedPane.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) return
                    pressIndex = tabbedPane.indexAtLocation(e.x, e.y)
                    pressPoint = e.point
                    panel?.setActiveContainer(this@TabContainer)
                }

                override fun mouseReleased(e: MouseEvent) {
                    pressIndex = -1
                    pressPoint = null
                    tabbedPane.cursor = Cursor.getDefaultCursor()
                    val p = panel ?: return
                    if (p.isDragging()) {
                        p.finishDrag(e.locationOnScreen)
                    }
                }
            })

            tabbedPane.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val p = panel ?: return
                    if (p.isDragging()) {
                        p.updateDrag(e.locationOnScreen)
                        tabbedPane.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        return
                    }
                    // 尚未开始拖拽：判断是否越过拖动阈值
                    val start = pressPoint ?: return
                    if (pressIndex < 0 || pressIndex >= tabs.size) return
                    if (e.point.distance(start) < DRAG_THRESHOLD) return
                    val tab = tabs[pressIndex]
                    tabbedPane.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    p.beginDrag(tab, this@TabContainer, e.locationOnScreen)
                }
            })
        }

        /**
         * 计算屏幕坐标在本容器 Tab 栏中的落点索引：越过命中 Tab 的水平中点则落到其后，否则落到其前；
         * 未命中任何 Tab（空白区）落到末尾。占位标签不参与命中计算。
         */
        internal fun dropIndexAt(screenPoint: Point): Int {
            val local = Point(screenPoint).apply { SwingUtilities.convertPointFromScreen(this, tabbedPane) }
            for (i in 0 until tabbedPane.tabCount) {
                if (i == placeholderIndex) continue
                val bounds = tabbedPane.getBoundsAt(i) ?: continue
                if (!bounds.contains(local)) continue
                val realIndex = toRealIndex(i)
                return if (local.x <= bounds.x + bounds.width / 2) realIndex else realIndex + 1
            }
            return tabs.size
        }

        /**
         * 把 tabbedPane 的显示索引换算为 tabs 列表的真实索引（跳过占位标签）。
         */
        private fun toRealIndex(displayIndex: Int): Int {
            return if (placeholderIndex in 0..displayIndex) displayIndex - 1 else displayIndex
        }

        /**
         * 判断鼠标屏幕坐标是否落在当前占位标签的显示区域内。
         * 用于打破“占位推开真实标签 -> 按坐标重算落点 -> 落点翻转”的闪烁环：
         * 只要鼠标还停在占位标签范围内，落点就保持不动，不触发重算。
         */
        internal fun isPointerOverPlaceholder(screenPoint: Point): Boolean {
            if (placeholderIndex < 0 || placeholderIndex >= tabbedPane.tabCount) return false
            val bounds = tabbedPane.getBoundsAt(placeholderIndex) ?: return false
            val local = Point(screenPoint).apply { SwingUtilities.convertPointFromScreen(this, tabbedPane) }
            return bounds.contains(local)
        }

        // ======== 占位标签 ========

        internal fun placeholderIndex(): Int = placeholderIndex

        /**
         * 在指定真实索引处显示占位标签（若已存在则先移除再插入）。
         */
        internal fun showPlaceholder(dragged: TabInfo, index: Int) {
            removePlaceholder()
            val safeIndex = index.coerceIn(0, tabs.size)
            val holder = JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(6, 1)
            }
            placeholderComponent = holder
            placeholderIndex = safeIndex
            tabbedPane.insertTab(null, null, holder, null, safeIndex)
            tabbedPane.setTabComponentAt(safeIndex, createPlaceholderLabel(dragged))
            setDropHighlight(true)
        }

        internal fun removePlaceholder() {
            if (placeholderIndex < 0) return
            val idx = placeholderIndex
            placeholderIndex = -1
            placeholderComponent = null
            if (idx < tabbedPane.tabCount) {
                tabbedPane.removeTabAt(idx)
            }
            setDropHighlight(false)
        }

        private fun createPlaceholderLabel(dragged: TabInfo): JComponent {
            val accent = UIManager.getColor("Component.focusColor") ?: Color(0x35, 0x74, 0xF0)
            return object : JPanel(BorderLayout(5, 0)) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    // 半透明主题色底 + 实色圆角描边，形成柔和的“落点胶囊”
                    g2.color = Color(accent.red, accent.green, accent.blue, 40)
                    g2.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
                    g2.color = accent
                    g2.stroke = BasicStroke(1.3f)
                    g2.drawRoundRect(1, 1, width - 3, height - 3, 8, 8)
                    g2.dispose()
                }
            }.apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                add(JLabel(dragged.icon), BorderLayout.WEST)
                add(JLabel(dragged.title).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = accent
                }, BorderLayout.CENTER)
            }
        }

        private fun setDropHighlight(highlight: Boolean) {
            if (isDropTarget != highlight) {
                isDropTarget = highlight
                tabbedPane.border = if (highlight) dropHighlightBorder else normalBorder
                tabbedPane.repaint()
            }
        }

        // ======== Tab 增删（拖拽用） ========

        /**
         * 从本容器分离一个 Tab（仅移除，不 dispose 组件），用于拖拽开始。
         */
        internal fun detachTab(tab: TabInfo) {
            val index = tabs.indexOf(tab)
            if (index < 0) return
            tabs.removeAt(index)
            if (index < tabbedPane.tabCount) {
                tabbedPane.removeTabAt(index)
            }
        }

        /**
         * 拖拽落位：先移除可能存在的占位标签，再把真实 Tab 插入到指定位置。
         */
        internal fun insertRealTab(tab: TabInfo, index: Int, highlight: Boolean) {
            removePlaceholder()
            val insertIndex = index.coerceIn(0, tabs.size)
            tabs.add(insertIndex, tab)
            tabbedPane.insertTab(tab.title, tab.icon, tab.component, tab.tooltip, insertIndex)
            tabbedPane.setTabComponentAt(insertIndex, createTabComponent(tab))
            tabbedPane.selectedIndex = insertIndex
            panel?.setActiveContainer(this)
            if (highlight) highlightTab(tab)
        }

        /**
         * 拖拽分离 Tab 后若本容器已空，尝试与兄弟容器合并（保持无限分屏结构收敛）。
         */
        internal fun tryMergeAfterDetach() {
            if (tabs.isEmpty() && !isSplit()) tryMergeWithSibling()
        }

        // ======== 右键菜单与激活 ========

        private fun setupTabEvents() {
            tabbedPane.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    panel?.setActiveContainer(this@TabContainer)
                }
                override fun mousePressed(e: MouseEvent) = handlePopup(e)
                override fun mouseReleased(e: MouseEvent) = handlePopup(e)
                private fun handlePopup(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val tabIndex = tabbedPane.indexAtLocation(e.x, e.y)
                    if (tabIndex < 0 || tabIndex >= tabs.size) return
                    tabbedPane.selectedIndex = tabIndex
                    showTabContextMenu(e, tabIndex)
                }
            })

            tabbedPane.addChangeListener {
                panel?.setActiveContainer(this@TabContainer)
            }
        }

        private fun showTabContextMenu(e: MouseEvent, tabIndex: Int) {
            val multiTab = tabs.size > 1
            val group = DefaultActionGroup().apply {
                add(AnActionFactory.create("关闭", PluginIcons.Close) { closeTab(tabIndex) })
                if (multiTab) {
                    add(AnActionFactory.create("关闭其他", PluginIcons.CloseOthers) { closeOtherTabs(tabIndex) })
                }
                add(AnActionFactory.create("关闭所有", PluginIcons.CloseAll) { closeAllTabs() })
                if (multiTab) {
                    addSeparator()
                    add(AnActionFactory.create("向右拆分", PluginIcons.SplitVertical) { splitRight(tabIndex) })
                    add(AnActionFactory.create("向下拆分", PluginIcons.SplitHorizontal) { splitDown(tabIndex) })
                }
                if (parentContainer != null) {
                    addSeparator()
                    add(AnActionFactory.create("取消分屏", PluginIcons.Unsplit) { unsplit() })
                }
            }
            JBPopupFactory.getInstance()
                .createActionGroupPopup(null, group, DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
                .show(RelativePoint(e.component, Point(e.x, e.y)))
        }

        fun addTab(tab: TabInfo) {
            tabs.add(tab)
            val index = tabbedPane.tabCount
            tabbedPane.addTab(tab.title, tab.icon, tab.component, tab.tooltip)
            tabbedPane.setTabComponentAt(index, createTabComponent(tab))
            tabbedPane.selectedIndex = index
            panel?.setActiveContainer(this)
        }

        /**
         * 临时高亮指定 Tab 的标题（加粗+主题色）约 1.5 秒后恢复，
         * 用于在拖拽放入后快速定位目标 Tab，尤其是多个 Tab 同名时。
         */
        private fun highlightTab(tab: TabInfo) {
            val index = tabs.indexOf(tab)
            if (index < 0) return
            val titleLabel = (tabbedPane.getTabComponentAt(index) as? JComponent)
                ?.let { findTitleLabel(it) } ?: return
            val originalFont = titleLabel.font
            val originalColor = titleLabel.foreground
            titleLabel.font = originalFont.deriveFont(Font.BOLD)
            titleLabel.foreground = UIManager.getColor("Component.focusColor") ?: Color.BLUE
            Timer(1500) {
                titleLabel.font = originalFont
                titleLabel.foreground = originalColor
            }.apply { isRepeats = false }.start()
        }

        /**
         * 在 Tab 组件中找到承载标题文本的 JLabel（约定为非空文本的 JLabel）。
         */
        private fun findTitleLabel(component: JComponent): JLabel? {
            if (component is JLabel && !component.text.isNullOrEmpty()) return component
            for (child in component.components) {
                if (child is JComponent) {
                    findTitleLabel(child)?.let { return it }
                }
            }
            return null
        }

        private fun createTabComponent(tab: TabInfo): JPanel {
            return JPanel(BorderLayout(5, 0)).apply {
                isOpaque = false
                add(JPanel(BorderLayout(3, 0)).apply {
                    isOpaque = false
                    add(JLabel(tab.icon), BorderLayout.WEST)
                    add(JLabel(tab.title), BorderLayout.CENTER)
                }, BorderLayout.CENTER)

                add(JLabel(PluginIcons.Close).apply {
                    toolTipText = "关闭"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            val idx = tabs.indexOfFirst { it.component == tab.component }
                            if (idx >= 0) closeTab(idx)
                        }
                        override fun mouseEntered(e: MouseEvent) { icon = PluginIcons.Close }
                        override fun mouseExited(e: MouseEvent) { icon = PluginIcons.Close }
                    })
                }, BorderLayout.EAST)
            }
        }

        private fun rebuildTabs() {
            val selectedIdx = tabbedPane.selectedIndex
            tabbedPane.removeAll()
            tabs.forEachIndexed { index, tab ->
                tabbedPane.addTab(tab.title, tab.icon, tab.component, tab.tooltip)
                tabbedPane.setTabComponentAt(index, createTabComponent(tab))
            }
            if (selectedIdx in tabs.indices) tabbedPane.selectedIndex = selectedIdx
        }

        private fun closeTab(index: Int) {
            if (index !in tabs.indices) return
            val tab = tabs.removeAt(index)
            tabbedPane.removeTabAt(index)
            if (tab.component is Disposable) Disposer.dispose(tab.component)
            if (tabs.isEmpty()) tryMergeWithSibling()
        }

        private fun closeOtherTabs(keepIndex: Int) {
            val keepTab = tabs[keepIndex]
            tabs.filter { it != keepTab }.forEach { if (it.component is Disposable) Disposer.dispose(it.component) }
            tabs.clear()
            tabs.add(keepTab)
            rebuildTabs()
        }

        private fun closeAllTabs() {
            tabs.forEach { if (it.component is Disposable) Disposer.dispose(it.component) }
            tabs.clear()
            tabbedPane.removeAll()
            tryMergeWithSibling()
        }

        private fun splitRight(tabIndex: Int) = split(tabIndex, false)
        private fun splitDown(tabIndex: Int) = split(tabIndex, true)

        private fun split(tabIndex: Int, vertical: Boolean) {
            if (tabs.size <= 1) return
            val tabToMove = tabs.removeAt(tabIndex)
            tabbedPane.removeTabAt(tabIndex)

            remove(tabbedPane)
            splitPane = JBSplitter(vertical, 0.5f).apply { dividerWidth = 3 }

            firstChild = TabContainer(panel).also { child ->
                child.parentContainer = this
                tabs.toList().forEach { child.addTab(it) }
            }
            secondChild = TabContainer(panel).also { child ->
                child.parentContainer = this
                child.addTab(tabToMove)
            }

            tabs.clear()
            tabbedPane.removeAll()

            splitPane!!.firstComponent = firstChild
            splitPane!!.secondComponent = secondChild
            add(splitPane!!, BorderLayout.CENTER)

            // 设置新分出的容器为活动容器
            panel?.setActiveContainer(secondChild)

            revalidate()
            repaint()
        }

        /**
         * 取消分屏 - 将所有Tab合并到一个容器
         */
        private fun unsplit() {
            val parent = parentContainer ?: return

            // 收集兄弟容器的所有Tab
            val sibling = if (parent.firstChild == this) parent.secondChild else parent.firstChild
            val allTabs = mutableListOf<TabInfo>()

            collectAllTabs(this, allTabs)
            sibling?.let { collectAllTabs(it, allTabs) }

            // 在父容器中重建
            parent.remove(parent.splitPane)
            parent.splitPane = null
            parent.firstChild?.also { it.tabs.clear() }
            parent.secondChild?.also { it.tabs.clear() }
            parent.firstChild = null
            parent.secondChild = null

            parent.add(parent.tabbedPane, BorderLayout.CENTER)
            allTabs.forEach { parent.addTab(it) }

            panel?.setActiveContainer(parent)
            parent.revalidate()
            parent.repaint()
        }

        private fun collectAllTabs(container: TabContainer, result: MutableList<TabInfo>) {
            if (container.isSplit()) {
                container.firstChild?.let { collectAllTabs(it, result) }
                container.secondChild?.let { collectAllTabs(it, result) }
            } else {
                result.addAll(container.tabs)
            }
        }

        /**
         * 当Tab全部关闭时，尝试与兄弟容器合并
         */
        private fun tryMergeWithSibling() {
            val parent = parentContainer ?: return
            val sibling = if (parent.firstChild == this) parent.secondChild else parent.firstChild
            sibling ?: return

            parent.remove(parent.splitPane)
            parent.splitPane = null
            parent.firstChild = null
            parent.secondChild = null

            if (sibling.isSplit()) {
                // 兄弟是分屏的，提升兄弟的分屏结构
                parent.splitPane = sibling.splitPane
                parent.firstChild = sibling.firstChild?.also { it.parentContainer = parent }
                parent.secondChild = sibling.secondChild?.also { it.parentContainer = parent }
                parent.add(parent.splitPane!!, BorderLayout.CENTER)
            } else {
                // 兄弟不是分屏的，合并Tab
                sibling.tabs.forEach { parent.addTab(it) }
                parent.add(parent.tabbedPane, BorderLayout.CENTER)
            }

            panel?.setActiveContainer(if (parent.isSplit()) parent.firstChild else parent)
            parent.revalidate()
            parent.repaint()

            // 如果父容器也空了，继续向上合并
            if (parent.tabs.isEmpty() && !parent.isSplit()) {
                parent.tryMergeWithSibling()
            }
        }

        fun getTotalTabCount(): Int {
            return tabs.size + (firstChild?.getTotalTabCount() ?: 0) + (secondChild?.getTotalTabCount() ?: 0)
        }

        override fun dispose() {
            tabs.forEach { if (it.component is Disposable) Disposer.dispose(it.component) }
            firstChild?.dispose()
            secondChild?.dispose()
        }

        companion object {
            private const val DRAG_THRESHOLD = 5.0
        }
    }
}
