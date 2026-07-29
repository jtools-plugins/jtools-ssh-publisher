package com.lhstack.ssh.component

import com.intellij.ui.table.JBTable
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.table.TableModel

/**
 * 布尔选择列在鼠标按下时直接更新模型的表格。
 *
 * JTable 默认 Boolean 编辑器需要先进入单元格编辑状态，再由编辑器提交值；
 * 在滚动容器、折叠面板等复合界面中，焦点切换可能中断这条链路，表现为
 * Checkbox 点击后没有切换。这里在表格边界直接完成一次模型更新，键盘编辑仍
 * 交给 JTable 默认行为处理。
 */
class ImmediateBooleanTable(model: TableModel) : JBTable(model) {

    private var pressedBooleanCell: Point? = null
    private var suppressClickForBooleanCell = false

    override fun processMouseEvent(event: MouseEvent) {
        if (SwingUtilities.isLeftMouseButton(event)) {
            when (event.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    val viewRow = rowAtPoint(event.point)
                    val viewColumn = columnAtPoint(event.point)
                    if (isEditableBooleanCell(viewRow, viewColumn)) {
                        toggleBooleanCell(viewRow, viewColumn)
                        pressedBooleanCell = Point(viewColumn, viewRow)
                        suppressClickForBooleanCell = true
                        event.consume()
                        return
                    }
                }

                MouseEvent.MOUSE_RELEASED -> {
                    if (pressedBooleanCell != null) {
                        pressedBooleanCell = null
                        event.consume()
                        return
                    }
                }

                MouseEvent.MOUSE_CLICKED -> {
                    if (suppressClickForBooleanCell) {
                        suppressClickForBooleanCell = false
                        event.consume()
                        return
                    }
                }
            }
        }
        super.processMouseEvent(event)
    }

    private fun isEditableBooleanCell(viewRow: Int, viewColumn: Int): Boolean {
        if (viewRow < 0 || viewColumn < 0) return false
        val modelRow = convertRowIndexToModel(viewRow)
        val modelColumn = convertColumnIndexToModel(viewColumn)
        return model.isCellEditable(modelRow, modelColumn) &&
            model.getColumnClass(modelColumn) == java.lang.Boolean::class.java
    }

    private fun toggleBooleanCell(viewRow: Int, viewColumn: Int) {
        val modelRow = convertRowIndexToModel(viewRow)
        val modelColumn = convertColumnIndexToModel(viewColumn)
        val currentValue = model.getValueAt(modelRow, modelColumn) as Boolean
        changeSelection(viewRow, viewColumn, false, false)
        model.setValueAt(!currentValue, modelRow, modelColumn)
        repaint(getCellRect(viewRow, viewColumn, false))
    }
}
