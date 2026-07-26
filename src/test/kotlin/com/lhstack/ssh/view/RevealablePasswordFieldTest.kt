package com.lhstack.ssh.view

import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RevealablePasswordFieldTest {

    @Test
    fun reveal_switches_to_plain_text_field_with_shared_content() {
        SwingUtilities.invokeAndWait {
            val field = RevealablePasswordField("secret")
            val maskedField = field.components.filterIsInstance<JBPasswordField>().single()
            val plainField = field.components.filterIsInstance<JBTextField>().single()

            assertSame(maskedField.document, plainField.document)
            assertTrue(maskedField.isVisible)
            assertFalse(plainField.isVisible)

            field.setRevealed(true)

            assertTrue(field.isRevealed)
            assertFalse(maskedField.isVisible)
            assertTrue(plainField.isVisible)
            assertContentEquals("secret".toCharArray(), field.password)

            plainField.text = "updated"
            assertContentEquals("updated".toCharArray(), field.password)
        }
    }

    @Test
    fun hiding_plain_text_restores_password_field_without_losing_content() {
        SwingUtilities.invokeAndWait {
            val field = RevealablePasswordField("secret")
            val maskedField = field.components.filterIsInstance<JBPasswordField>().single()
            val plainField = field.components.filterIsInstance<JBTextField>().single()

            field.setRevealed(true)
            plainField.select(1, 4)
            field.setRevealed(false)

            assertFalse(field.isRevealed)
            assertTrue(maskedField.isVisible)
            assertFalse(plainField.isVisible)
            assertContentEquals("secret".toCharArray(), field.password)
            assertTrue(maskedField.selectionStart == 1 && maskedField.selectionEnd == 4)
        }
    }
}
