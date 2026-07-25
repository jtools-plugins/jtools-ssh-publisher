package com.lhstack.ssh.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainViewCollapseStateTest {

    @Test
    fun collapse_stores_current_proportion_and_marks_hidden() {
        val state = MainViewCollapseState(defaultExpandedProportion = 0.4f, collapsedProportion = 0.02f)

        val appliedProportion = state.collapse(currentProportion = 0.35f)

        assertTrue(state.collapsed)
        assertEquals(0.35f, state.lastExpandedProportion)
        assertEquals(0.02f, appliedProportion)
    }

    @Test
    fun expand_restores_last_expanded_proportion() {
        val state = MainViewCollapseState(defaultExpandedProportion = 0.4f, collapsedProportion = 0.02f)
        state.collapse(currentProportion = 0.33f)

        val appliedProportion = state.expand()

        assertFalse(state.collapsed)
        assertEquals(0.33f, appliedProportion)
    }

    @Test
    fun collapse_hides_top_panel_until_expand() {
        val state = MainViewCollapseState(defaultExpandedProportion = 0.4f, collapsedProportion = 0.02f)

        state.collapse(currentProportion = 0.33f)
        assertFalse(state.topPanelVisible)
        assertFalse(state.topPanelControlsVisible)

        state.expand()
        assertTrue(state.topPanelVisible)
        assertTrue(state.topPanelControlsVisible)
    }

    @Test
    fun collapse_uses_default_proportion_when_current_value_is_invalid() {
        val state = MainViewCollapseState(defaultExpandedProportion = 0.4f, collapsedProportion = 0.02f)

        state.collapse(currentProportion = 1.2f)
        val appliedProportion = state.expand()

        assertEquals(0.4f, state.lastExpandedProportion)
        assertEquals(0.4f, appliedProportion)
    }
}
