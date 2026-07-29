package com.lhstack.ssh.component

import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImmediateBooleanTableTest {

    @Test
    fun pressing_boolean_cell_updates_model_once() {
        SwingUtilities.invokeAndWait {
            val model = BooleanCellModel()
            val table = ImmediateBooleanTable(model).apply {
                setSize(300, 30)
                doLayout()
            }
            val cell = table.getCellRect(0, 0, true)
            val x = cell.x + cell.width / 2
            val y = cell.y + cell.height / 2

            dispatchLeftMouse(table, MouseEvent.MOUSE_PRESSED, x, y)
            assertTrue(model.selected)

            dispatchLeftMouse(table, MouseEvent.MOUSE_RELEASED, x, y)
            dispatchLeftMouse(table, MouseEvent.MOUSE_CLICKED, x, y)
            assertTrue(model.selected)
        }
    }

    @Test
    fun pressing_non_boolean_cell_does_not_update_selection() {
        SwingUtilities.invokeAndWait {
            val model = BooleanCellModel()
            val table = ImmediateBooleanTable(model).apply {
                setSize(300, 30)
                doLayout()
            }
            val cell = table.getCellRect(0, 1, true)

            dispatchLeftMouse(table, MouseEvent.MOUSE_PRESSED, cell.x + 2, cell.y + 2)

            assertFalse(model.selected)
        }
    }

    private fun dispatchLeftMouse(table: ImmediateBooleanTable, id: Int, x: Int, y: Int) {
        table.dispatchEvent(
            MouseEvent(
                table,
                id,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1
            )
        )
    }

    private class BooleanCellModel : AbstractTableModel() {
        var selected = false

        override fun getRowCount() = 1
        override fun getColumnCount() = 2
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) selected else "script"

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0) {
                selected = aValue as Boolean
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }
    }
}
