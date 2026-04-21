package com.lhstack.ssh.util

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import javax.swing.Icon

object AnActionFactory {
    fun create(title:String, icon: Icon, actionThread: ActionUpdateThread = ActionUpdateThread.EDT, invoke: (AnActionEvent) -> Unit): AnAction = object : AnAction({title},icon) {
        override fun actionPerformed(p0: AnActionEvent) {
            invoke(p0)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = actionThread
    }
}