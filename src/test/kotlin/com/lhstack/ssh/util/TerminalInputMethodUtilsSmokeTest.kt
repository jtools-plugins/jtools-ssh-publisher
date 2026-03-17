package com.lhstack.ssh.util

import java.awt.Rectangle
import java.text.AttributedCharacterIterator

fun main() {
    val requests = SafeInputMethodRequests(
        textLocationProvider = { Rectangle(10, 20, 30, 40) },
        insertPositionSupplier = { 3 }
    )

    check(requests.getTextLocation(null) == Rectangle(10, 20, 30, 40)) {
        "text location should come from provider"
    }
    check(requests.getInsertPositionOffset() == 3) {
        "insert position should come from supplier"
    }

    val committedText = requests.getCommittedText(0, 0, emptyArray<AttributedCharacterIterator.Attribute>())
    check(committedText.beginIndex == 0) { "empty committed text should start at 0" }
    check(committedText.endIndex == 0) { "empty committed text should end at 0" }
    check(committedText.first().code == 65535) { "empty committed text should be done" }

    val cancelledText = requests.cancelLatestCommittedText(emptyArray<AttributedCharacterIterator.Attribute>())
    check(cancelledText.beginIndex == 0) { "empty cancelled text should start at 0" }
    check(cancelledText.endIndex == 0) { "empty cancelled text should end at 0" }
    check(cancelledText.first().code == 65535) { "empty cancelled text should be done" }

    val selectedText = requests.getSelectedText(emptyArray<AttributedCharacterIterator.Attribute>())
    check(selectedText.beginIndex == 0) { "empty selected text should start at 0" }
    check(selectedText.endIndex == 0) { "empty selected text should end at 0" }
    check(selectedText.first().code == 65535) { "empty selected text should be done" }
}
