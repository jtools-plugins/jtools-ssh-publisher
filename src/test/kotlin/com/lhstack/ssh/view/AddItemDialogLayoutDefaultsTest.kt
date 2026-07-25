package com.lhstack.ssh.view

import java.awt.GridBagConstraints
import kotlin.test.Test
import kotlin.test.assertEquals

class AddItemDialogLayoutDefaultsTest {

    @Test
    fun main_form_constraints_keep_labels_vertically_centered() {
        val constraints = AddItemDialogLayoutDefaults.mainFormConstraints()

        assertEquals(GridBagConstraints.HORIZONTAL, constraints.fill)
        assertEquals(GridBagConstraints.WEST, constraints.anchor)
    }
}
