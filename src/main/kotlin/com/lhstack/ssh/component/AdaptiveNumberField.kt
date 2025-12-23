package com.lhstack.ssh.component

import java.awt.Dimension
import java.awt.FontMetrics
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

class AdaptiveNumberField : JTextField("22") {
    companion object {
        private const val MAX_VALUE = 65535
        private const val MIN_WIDTH = 60
        private const val MAX_WIDTH = 60
    }

    init {
        horizontalAlignment = JTextField.LEFT
        setupDocumentFilter()
        setupWidthAdjustment()

        // 初始化尺寸
        minimumSize = Dimension(MIN_WIDTH, 35)  // 设置最小高度
        preferredSize = Dimension(MIN_WIDTH, 35)
        maximumSize = Dimension(MAX_WIDTH, 35)  // 初始最大值
    }

    private fun setupDocumentFilter() {
        (document as? AbstractDocument)?.setDocumentFilter(object : DocumentFilter() {
            override fun insertString(
                fb: FilterBypass,
                offset: Int,
                text: String,
                attrs: AttributeSet?
            ) {
                handleModification(fb, offset, 0, text,attrs)
            }

            override fun replace(
                fb: FilterBypass,
                offset: Int,
                length: Int,
                text: String?,
                attrs: AttributeSet?
            ) {
                handleModification(fb, offset, length, text ?: "",attrs)
            }

            private fun handleModification(
                fb: FilterBypass,
                offset: Int,
                length: Int,
                text: String,
                attrs: AttributeSet? //
            ) {
                val newText = buildString {
                    append(document.getText(0, document.length))
                    delete(offset, offset + length)
                    insert(offset, text)
                }

                if (isValidInput(newText)) {
                    super.replace(fb, offset, length, text, attrs)
                }
            }

            private fun isValidInput(input: String): Boolean {
                if (input.isEmpty()) return true
                if (!input.matches(Regex("\\d+"))) return false
                return input.toIntOrNull()?.let { it in 0..MAX_VALUE } ?: false
            }
        })
    }

    private fun setupWidthAdjustment() {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = adjustWidth()
            override fun removeUpdate(e: DocumentEvent) = adjustWidth()
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
    }

    private fun adjustWidth() {
        val metrics: FontMetrics = getFontMetrics(font)
        val textWidth = metrics.stringWidth(text.takeIf { it.isNotEmpty() } ?: "0")
        val newWidth = (textWidth + 10).coerceIn(MIN_WIDTH, MAX_WIDTH)

        // 显式设置所有尺寸参数
        preferredSize = Dimension(newWidth, preferredSize.height)
        maximumSize = Dimension(newWidth, preferredSize.height) // 关键修改
        minimumSize = Dimension(MIN_WIDTH, preferredSize.height)

        revalidate()
        repaint()
    }
}