package com.lhstack.ssh.view

import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.jediterm.terminal.model.TerminalTypeAheadSettings

object SshTerminalTypeAhead {

    fun disabled(defaults: TerminalTypeAheadSettings = TerminalTypeAheadSettings.DEFAULT): TerminalTypeAheadSettings {
        return TerminalTypeAheadSettings(false, defaults.latencyThreshold, defaults.typeAheadStyle)
    }
}

class SshTerminalSettingsProvider : JBTerminalSystemSettingsProviderBase() {

    override fun getTypeAheadSettings(): TerminalTypeAheadSettings {
        return SshTerminalTypeAhead.disabled()
    }
}
