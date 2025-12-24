package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
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

    init {
        Disposer.register(parentDisposable, this)
        rootContainer = TabContainer(this, null)
        add(rootContainer, BorderLayout.CENTER)
    }

    fun addTab(title: String, icon: Icon, component: JComponent, tooltip: String) {
        rootContainer.addTab(TabInfo(title, icon, component, tooltip))
    }

    fun getTabCount(): Int = rootContainer.getTotalTabCount()

    override fun dispose() {
        rootContainer.dispose()
    }

    internal fun setRootContainer(container: TabContainer) {
        remove(rootContainer)
        rootContainer = container
        container.parentPanel = this
        container.parentContainer = null
        add(rootContainer, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    data class TabInfo(val title: String, val icon: Icon, val component: JComponent, val tooltip: String)

    class TabContainer(
        internal var parentPanel: DockableTabPanel?,
        internal var parentContainer: TabContainer?
    ) : JPanel(BorderLayout()), Disposable {

        private val tabbedPane = JTabbedPane(JTabbedPane.TOP)
        private val tabs = mutableListOf<TabInfo>()
        private var splitPane: JBSplitter? = null
        private var firstChild: TabContainer? = null
        private var secondChild: TabContainer? = null
        private var dragIndex = -1

        init {
            add(tabbedPane, BorderLayout.CENTER)
            setupDragReorder()
            setupTabRightClick()
        }

        private fun setupDragReorder() {
            tabbedPane.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragIndex = tabbedPane.indexAtLocation(e.x, e.y)
                }
                override fun mouseReleased(e: MouseEvent) {
                    dragIndex = -1
                    tabbedPane.cursor = Cursor.getDefaultCursor()
                }
            })

            tabbedPane.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (dragIndex < 0 || dragIndex >= tabs.size) return
                    val targetIndex = tabbedPane.indexAtLocation(e.x, e.y)
                    if (targetIndex >= 0 && targetIndex != dragIndex && targetIndex < tabs.size) {
                        val tab = tabs.removeAt(dragIndex)
                        tabs.add(targetIndex, tab)
                        rebuildTabs()
                        tabbedPane.selectedIndex = targetIndex
                        dragIndex = targetIndex
                    }
                    tabbedPane.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            })
        }

        private fun setupTabRightClick() {
            tabbedPane.addMouseListener(object : MouseAdapter() {
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
            }.show(e.component, e.x, e.y)
        }

        fun addTab(tab: TabInfo) {
            tabs.add(tab)
            val index = tabbedPane.tabCount
            tabbedPane.addTab(tab.title, tab.icon, tab.component, tab.tooltip)
            tabbedPane.setTabComponentAt(index, createTabComponent(tab))
            tabbedPane.selectedIndex = index
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
            if (tabs.isEmpty()) tryMergeWithParent()
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
            tryMergeWithParent()
        }

        private fun splitRight(tabIndex: Int) = split(tabIndex, false)
        private fun splitDown(tabIndex: Int) = split(tabIndex, true)

        private fun split(tabIndex: Int, vertical: Boolean) {
            if (tabs.size <= 1) return
            val tabToMove = tabs.removeAt(tabIndex)
            tabbedPane.removeTabAt(tabIndex)

            remove(tabbedPane)
            splitPane = JBSplitter(vertical, 0.5f).apply { dividerWidth = 3 }

            firstChild = TabContainer(null, this).also { tabs.forEach { tab -> it.addTab(tab) } }
            secondChild = TabContainer(null, this).also { it.addTab(tabToMove) }

            tabs.clear()
            tabbedPane.removeAll()

            splitPane!!.firstComponent = firstChild
            splitPane!!.secondComponent = secondChild
            add(splitPane!!, BorderLayout.CENTER)
            revalidate()
            repaint()
        }

        private fun tryMergeWithParent() {
            val parent = parentContainer ?: return
            val sibling = if (parent.firstChild == this) parent.secondChild else parent.firstChild
            sibling ?: return

            parent.remove(parent.splitPane)
            parent.splitPane = null
            parent.firstChild = null
            parent.secondChild = null

            if (sibling.splitPane != null) {
                parent.splitPane = sibling.splitPane
                parent.firstChild = sibling.firstChild?.also { it.parentContainer = parent }
                parent.secondChild = sibling.secondChild?.also { it.parentContainer = parent }
                parent.add(parent.splitPane!!, BorderLayout.CENTER)
            } else {
                sibling.tabs.forEach { parent.addTab(it) }
                parent.add(parent.tabbedPane, BorderLayout.CENTER)
            }

            parent.revalidate()
            parent.repaint()

            if (parent.tabs.isEmpty() && parent.splitPane == null) {
                parent.tryMergeWithParent()
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
