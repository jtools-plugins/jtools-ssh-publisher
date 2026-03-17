package com.lhstack.ssh.view

import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.TerminalTypeAheadSettings

fun main() {
    val defaults = TerminalTypeAheadSettings(true, 123L, TextStyle())
    val settings = SshTerminalTypeAhead.disabled(defaults)

    check(!settings.isEnabled) {
        "SSH terminal should disable type-ahead to avoid local echo corruption during large paste"
    }
    check(settings.latencyThreshold == 123L) {
        "latency threshold should preserve defaults"
    }
    check(settings.typeAheadStyle == defaults.typeAheadStyle) {
        "type-ahead style should preserve defaults"
    }
}
