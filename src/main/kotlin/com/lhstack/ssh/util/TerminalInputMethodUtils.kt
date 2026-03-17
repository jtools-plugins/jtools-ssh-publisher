package com.lhstack.ssh.util

import java.awt.Rectangle
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.text.AttributedCharacterIterator
import java.text.AttributedString

object TerminalInputMethodUtils {

    fun emptyAttributedText(): AttributedCharacterIterator {
        return AttributedString("").iterator
    }
}

class SafeInputMethodRequests(
    private val textLocationProvider: (TextHitInfo?) -> Rectangle = { Rectangle() },
    private val insertPositionSupplier: () -> Int = { 0 },
    private val selectedTextProvider: () -> AttributedCharacterIterator = { TerminalInputMethodUtils.emptyAttributedText() }
) : InputMethodRequests {

    override fun getTextLocation(offset: TextHitInfo?): Rectangle {
        return Rectangle(textLocationProvider(offset))
    }

    override fun getLocationOffset(x: Int, y: Int): TextHitInfo? = null

    override fun getInsertPositionOffset(): Int = insertPositionSupplier()

    override fun getCommittedText(
        beginIndex: Int,
        endIndex: Int,
        attributes: Array<out AttributedCharacterIterator.Attribute>?
    ): AttributedCharacterIterator {
        return TerminalInputMethodUtils.emptyAttributedText()
    }

    override fun getCommittedTextLength(): Int = 0

    override fun cancelLatestCommittedText(
        attributes: Array<out AttributedCharacterIterator.Attribute>?
    ): AttributedCharacterIterator {
        return TerminalInputMethodUtils.emptyAttributedText()
    }

    override fun getSelectedText(
        attributes: Array<out AttributedCharacterIterator.Attribute>?
    ): AttributedCharacterIterator {
        return selectedTextProvider()
    }
}
