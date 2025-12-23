package com.lhstack.ssh

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.lhstack.ssh.view.MainView

/**
 * SSH Publisher 工具窗口工厂
 */
class SshToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainView = MainView(toolWindow.disposable, project)
        val content = ContentFactory.getInstance().createContent(mainView, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
