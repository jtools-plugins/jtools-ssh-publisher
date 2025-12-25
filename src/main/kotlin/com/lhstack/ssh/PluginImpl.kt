package com.lhstack.ssh

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.lhstack.ssh.view.MainView
import com.lhstack.tools.plugins.Helper
import com.lhstack.tools.plugins.IPlugin
import com.lhstack.tools.plugins.Logger
import javax.swing.Icon
import javax.swing.JComponent


class PluginImpl : IPlugin {

    companion object {
        val CACHE = mutableMapOf<String, JComponent>()
        val DISPOSERS = mutableMapOf<String, Disposable>()
        val LOGGERS = mutableMapOf<String, Logger>()
        val OPEN_THIS_PAGES = mutableMapOf<String, Runnable>()
    }

    override fun pluginIcon(): Icon = Helper.findIcon("pluginIcon.svg", PluginImpl::class.java)

    override fun pluginTabIcon(): Icon = Helper.findIcon("pluginTabIcon.svg", PluginImpl::class.java)

    override fun closeProject(project: Project) {
        DISPOSERS.remove(project.locationHash)?.let {
            Disposer.dispose(it)
        }
        LOGGERS.remove(project.locationHash)
        OPEN_THIS_PAGES.remove(project.locationHash)
        CACHE.remove(project.locationHash)
    }

    override fun openProject(project: Project, logger: Logger, openThisPage: Runnable) {
        LOGGERS[project.locationHash] = logger
        OPEN_THIS_PAGES[project.locationHash] = openThisPage
    }

    override fun createPanel(project: Project): JComponent {
        return CACHE.computeIfAbsent(project.locationHash) {
            val disposable = Disposer.newDisposable()
            DISPOSERS[project.locationHash] = disposable
            MainView(disposable, project)
        }
    }

    override fun pluginName(): String = "SshPublisher"

    override fun pluginDesc(): String = "SshPublisher"

    override fun pluginVersion(): String = "1.0.4"
}