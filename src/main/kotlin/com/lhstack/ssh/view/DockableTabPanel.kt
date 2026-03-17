package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*

/**
 * 支持拖拽排序和分屏的Tab面板
 */
class DockableTabPanel(private val parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {

    private var rootContainer: TabContainer
    private var activeContainer: TabContainer? = null
    
    // 跨容器拖拽状态
    internal var draggedTab: TabInfo? = null
    internal var dragSourceContainer: TabContainer? = null

    init {
        Disposer.register(parentDisposable, this)
        rootContainer = TabContainer(this)
        activeContainer = rootContainer
        add(rootContainer, BorderLayout.CENTER)
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
        rootContainer.dispose()
    }

    internal fun setActiveContainer(container: TabContainer?) {
        // 只有叶子容器（未分屏）才能成为活动容器
        if (container == null || !container.isSplit()) {
            activeContainer = container
        }
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
    
    /**
     * 清除拖拽状态
     */
    internal fun clearDragState() {
        draggedTab = null
        dragSourceContainer = null
    }

    data class TabInfo(val title: String, val icon: Icon, val component: JComponent, val tooltip: String)

    class TabContainer(private var panel: DockableTabPanel?) : JPanel(BorderLayout()), Disposable {

        private val tabbedPane = JTabbedPane(JTabbedPane.TOP)
        private val tabs = mutableListOf<TabInfo>()
        private var splitPane: JBSplitter? = null
        private var firstChild: TabContainer? = null
        private var secondChild: TabContainer? = null
        private var parentContainer: TabContainer? = null
        private var dragIndex = -1
        
        // 拖拽目标高亮
        private var isDropTarget = false
        private val dropHighlightBorder = BorderFactory.createLineBorder(UIManager.getColor("Component.focusColor") ?: Color.BLUE, 2)
        private val normalBorder: javax.swing.border.Border? = null

        init {
            add(tabbedPane, BorderLayout.CENTER)
            setupDragReorder()
            setupTabEvents()
            setupDropTarget()
        }

        fun isSplit() = splitPane != null
        fun getFirstChild() = firstChild
        fun getSecondChild() = secondChild
        fun setParentPanel(p: DockableTabPanel) { panel = p }

        private fun setupDragReorder() {
            tabbedPane.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragIndex = tabbedPane.indexAtLocation(e.x, e.y)
                    if (dragIndex >= 0 && dragIndex < tabs.size) {
                        // 开始跨容器拖拽
                        panel?.draggedTab = tabs[dragIndex]
                        panel?.dragSourceContainer = this@TabContainer
                    }
                    // 设置为活动容器
                    panel?.setActiveContainer(this@TabContainer)
                }
                override fun mouseReleased(e: MouseEvent) {
                    handleDrop(e)
                    dragIndex = -1
                    tabbedPane.cursor = Cursor.getDefaultCursor()
                    panel?.clearDragState()
                    clearAllDropHighlights()
                }
            })

            tabbedPane.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (dragIndex < 0 || dragIndex >= tabs.size) return
                    
                    // 获取屏幕坐标
                    val screenPoint = e.locationOnScreen
                    val targetContainer = panel?.findContainerAt(screenPoint)
                    
                    // 更新高亮状态
                    updateDropHighlight(targetContainer)
                    
                    if (targetContainer == this@TabContainer) {
                        // 同容器内拖拽排序
                        val targetIndex = tabbedPane.indexAtLocation(e.x, e.y)
                        if (targetIndex >= 0 && targetIndex != dragIndex && targetIndex < tabs.size) {
                            val tab = tabs.removeAt(dragIndex)
                            tabs.add(targetIndex, tab)
                            rebuildTabs()
                            tabbedPane.selectedIndex = targetIndex
                            dragIndex = targetIndex
                        }
                    }
                    
                    tabbedPane.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            })
        }
        
        private fun handleDrop(e: MouseEvent) {
            val draggedTab = panel?.draggedTab ?: return
            val sourceContainer = panel?.dragSourceContainer ?: return
            
            // 获取屏幕坐标找到目标容器
            val screenPoint = e.locationOnScreen
            val targetContainer = panel?.findContainerAt(screenPoint)
            
            // 如果目标容器不是源容器，执行跨容器移动
            if (targetContainer != null && targetContainer != sourceContainer) {
                // 从源容器移除
                val sourceIndex = sourceContainer.tabs.indexOf(draggedTab)
                if (sourceIndex >= 0) {
                    sourceContainer.tabs.removeAt(sourceIndex)
                    sourceContainer.tabbedPane.removeTabAt(sourceIndex)
                    
                    // 计算目标位置：将屏幕坐标转换为目标容器的本地坐标
                    val targetLocalPoint = Point(screenPoint).apply {
                        SwingUtilities.convertPointFromScreen(this, targetContainer.tabbedPane)
                    }
                    val targetIndex = targetContainer.tabbedPane.indexAtLocation(targetLocalPoint.x, targetLocalPoint.y)
                    
                    // 插入到目标容器的指定位置
                    targetContainer.insertTab(draggedTab, targetIndex)
                    
                    // 如果源容器空了，尝试合并
                    if (sourceContainer.tabs.isEmpty()) {
                        sourceContainer.tryMergeWithSibling()
                    }
                }
            }
        }
        
        private fun updateDropHighlight(targetContainer: TabContainer?) {
            // 清除所有高亮
            clearAllDropHighlights()
            
            // 如果目标容器不是源容器，高亮目标
            val sourceContainer = panel?.dragSourceContainer
            if (targetContainer != null && targetContainer != sourceContainer) {
                targetContainer.setDropHighlight(true)
            }
        }
        
        private fun clearAllDropHighlights() {
            panel?.getRootContainer()?.clearDropHighlightRecursive()
        }
        
        internal fun clearDropHighlightRecursive() {
            setDropHighlight(false)
            firstChild?.clearDropHighlightRecursive()
            secondChild?.clearDropHighlightRecursive()
        }
        
        private fun setDropHighlight(highlight: Boolean) {
            if (isDropTarget != highlight) {
                isDropTarget = highlight
                tabbedPane.border = if (highlight) dropHighlightBorder else normalBorder
                tabbedPane.repaint()
            }
        }
        
        private fun setupDropTarget() {
            // 允许在空白区域也能接收拖拽
            tabbedPane.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    val draggedTab = panel?.draggedTab
                    val sourceContainer = panel?.dragSourceContainer
                    if (draggedTab != null && sourceContainer != this@TabContainer) {
                        setDropHighlight(true)
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    setDropHighlight(false)
                }
            })
        }

        private fun setupTabEvents() {
            tabbedPane.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 点击Tab时设置为活动容器
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

            // Tab切换时设置活动容器
            tabbedPane.addChangeListener {
                panel?.setActiveContainer(this@TabContainer)
            }
        }

        private fun showTabContextMenu(e: MouseEvent, tabIndex: Int) {
            JPopupMenu().apply {
                add(JMenuItem("关闭", AllIcons.Actions.Close).apply {
                    addActionListener { closeTab(tabIndex) }
                })
                add(JMenuItem("关闭其他").apply {
                    addActionListener { closeOtherTabs(tabIndex) }
                    isEnabled = tabs.size > 1
                })
                add(JMenuItem("关闭所有").apply {
                    addActionListener { closeAllTabs() }
                })
                addSeparator()
                add(JMenuItem("向右拆分", AllIcons.Actions.SplitVertically).apply {
                    addActionListener { splitRight(tabIndex) }
                    isEnabled = tabs.size > 1
                })
                add(JMenuItem("向下拆分", AllIcons.Actions.SplitHorizontally).apply {
                    addActionListener { splitDown(tabIndex) }
                    isEnabled = tabs.size > 1
                })
                
                // 如果有父容器（说明已经分屏），显示取消分屏选项
                if (parentContainer != null) {
                    addSeparator()
                    add(JMenuItem("取消分屏", AllIcons.Actions.Cancel).apply {
                        addActionListener { unsplit() }
                    })
                }
            }.show(e.component, e.x, e.y)
        }

        fun addTab(tab: TabInfo) {
            tabs.add(tab)
            val index = tabbedPane.tabCount
            tabbedPane.addTab(tab.title, tab.icon, tab.component, tab.tooltip)
            tabbedPane.setTabComponentAt(index, createTabComponent(tab))
            tabbedPane.selectedIndex = index
            panel?.setActiveContainer(this)
            
            // 监听组件及其子组件的焦点，确保焦点时更新活动容器
            setupFocusTracking(tab.component)
        }
        
        /**
         * 递归为组件及其子组件添加焦点监听
         */
        private fun setupFocusTracking(component: Component) {
            component.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    panel?.setActiveContainer(this@TabContainer)
                }
            })
            if (component is Container) {
                component.components.forEach { setupFocusTracking(it) }
                // 监听后续添加的子组件
                component.addContainerListener(object : java.awt.event.ContainerAdapter() {
                    override fun componentAdded(e: java.awt.event.ContainerEvent) {
                        setupFocusTracking(e.child)
                    }
                })
            }
        }
        
        /**
         * 在指定位置插入Tab
         * @param tab 要插入的Tab
         * @param index 目标位置，-1 或超出范围则追加到末尾
         */
        fun insertTab(tab: TabInfo, index: Int) {
            val insertIndex = if (index < 0 || index > tabs.size) tabs.size else index
            tabs.add(insertIndex, tab)
            tabbedPane.insertTab(tab.title, tab.icon, tab.component, tab.tooltip, insertIndex)
            tabbedPane.setTabComponentAt(insertIndex, createTabComponent(tab))
            tabbedPane.selectedIndex = insertIndex
            panel?.setActiveContainer(this)
        }

        private fun createTabComponent(tab: TabInfo): JPanel {
            return JPanel(BorderLayout(5, 0)).apply {
                isOpaque = false
                add(JPanel(BorderLayout(3, 0)).apply {
                    isOpaque = false
                    add(JLabel(tab.icon), BorderLayout.WEST)
                    add(JLabel(tab.title), BorderLayout.CENTER)
                }, BorderLayout.CENTER)

                add(JLabel(AllIcons.Actions.Close).apply {
                    toolTipText = "关闭"
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            val idx = tabs.indexOfFirst { it.component == tab.component }
                            if (idx >= 0) closeTab(idx)
                        }
                        override fun mouseEntered(e: MouseEvent) { icon = AllIcons.Actions.CloseHovered }
                        override fun mouseExited(e: MouseEvent) { icon = AllIcons.Actions.Close }
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
    }
}
