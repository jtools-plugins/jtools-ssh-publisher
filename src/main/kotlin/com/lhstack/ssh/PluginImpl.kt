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

    override fun pluginDesc(): String = "IntelliJ IDEA SSH 客户端插件，集成终端、SFTP 文件浏览器、批量上传、上传模板、批量执行和传输管理，支持跳板链、远程文件同步、系统监控，以及拖拽上传/移动、导入覆盖、删除等高风险操作确认。上传支持前置/后置脚本，远程脚本经 SSH 在服务器执行，本地前置/后置脚本在本机执行，工作目录固定为当前项目根目录（project.basePath），脚本内相对路径均以项目根目录为基准；右键上传、批量上传、模板单次执行与模板批量执行四个入口行为一致。脚本选择 Checkbox 单击即可切换；批量执行点击分组或其下服务器时会回显所属分组脚本。"

    override fun pluginVersion(): String = "1.1.8"
}
