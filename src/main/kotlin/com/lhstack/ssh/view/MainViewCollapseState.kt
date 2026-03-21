package com.lhstack.ssh.view

class MainViewCollapseState(
    defaultExpandedProportion: Float,
    private val collapsedProportion: Float
) {

    var collapsed: Boolean = false
        private set

    val topPanelVisible: Boolean
        get() = !collapsed

    val topPanelControlsVisible: Boolean
        get() = !collapsed

    var lastExpandedProportion: Float = normalize(defaultExpandedProportion, 0.4f)
        private set

    init {
        require(collapsedProportion > 0f && collapsedProportion < 1f) {
            "collapsedProportion must be between 0 and 1"
        }
    }

    fun collapse(currentProportion: Float): Float {
        lastExpandedProportion = normalize(currentProportion, lastExpandedProportion)
        collapsed = true
        return collapsedProportion
    }

    fun expand(): Float {
        collapsed = false
        return lastExpandedProportion
    }

    private fun normalize(value: Float, fallback: Float): Float {
        return if (value > 0f && value < 1f) value else fallback
    }
}
