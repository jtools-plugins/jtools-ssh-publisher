package com.lhstack.ssh.component

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants

/** 对话框中脚本编辑器的默认高度，配合外层纵向滚动容器使用。 */
const val SCRIPT_EDITOR_HEIGHT = 150

/** 批量上传中服务器选择区的固定高度，超出部分由外层滚动条承载。 */
const val SERVER_SECTION_HEIGHT = 220

/**
 * 可折叠区块：点击标题行切换内容显示。
 *
 * [trailing] 用于放置属于该区块的附加控件（如 Shell 下拉），折叠时一并隐藏。
 */
class CollapsibleSection(
    title: String,
    private val content: JComponent,
    trailing: JComponent? = null,
    expanded: Boolean = true
) : JPanel(BorderLayout(0, 4)) {

    private val titleLabel = JBLabel(title)
    private val trailingHolder: JPanel? = trailing?.let {
        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(it)
        }
    }
    private var expandedState = expanded

    init {
        isOpaque = false
        add(createHeader(), BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        applyState()
    }

    fun toggle() = setExpanded(!expandedState)

    fun setExpanded(expand: Boolean) {
        if (expandedState == expand) return
        expandedState = expand
        applyState()
        revalidate()
        repaint()
    }

    private fun createHeader(): JComponent = JPanel(BorderLayout(6, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(titleLabel, BorderLayout.WEST)
        trailingHolder?.let { add(it, BorderLayout.EAST) }
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                toggle()
            }
        })
    }

    private fun applyState() {
        content.isVisible = expandedState
        trailingHolder?.isVisible = expandedState
        titleLabel.icon = if (expandedState) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
    }
}

/**
 * 纵向堆叠容器：宽度跟随视口，高度按子组件 preferredSize 累加，
 * 放入滚动面板后只出现纵向滚动条。
 */
class ScrollableVerticalPanel :
    JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 8, true, false)), Scrollable {

    init {
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int) = UNIT_INCREMENT

    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int) =
        visibleRect?.height ?: UNIT_INCREMENT

    override fun getScrollableTracksViewportWidth() = true

    override fun getScrollableTracksViewportHeight() = false

    private companion object {
        const val UNIT_INCREMENT = 16
    }
}

/** 把若干区块纵向排列，宽度撑满、高度按各自 preferredSize 累加。 */
fun verticalStack(vararg sections: JComponent): JComponent = ScrollableVerticalPanel().apply {
    sections.forEach { add(it) }
}

/**
 * 把若干区块纵向排列并套上纵向滚动条，用于高度受限的对话框内容区。
 *
 * 纵向策略为 AS_NEEDED：内容未溢出时不显示滚动条。
 */
fun verticalScrollPane(vararg sections: JComponent): JBScrollPane =
    JBScrollPane(verticalStack(*sections)).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = 16
    }
