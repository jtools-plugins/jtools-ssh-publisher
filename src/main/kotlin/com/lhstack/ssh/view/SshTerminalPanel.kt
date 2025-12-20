package com.lhstack.ssh.view

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.components.JBScrollBar
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.service.SshConnectionManager
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * SSH终端面板 - 使用JediTerm
 */
class SshTerminalPanel(
    val parentDisposable: Disposable,
    private val config: SshConfig,
    val project: Project
) : JPanel(BorderLayout()), Disposable {

    private var connectionManager = SshConnectionManager()
    private var shellChannel: ChannelShell? = null
    private var termWidget: JediTermWidget? = null

    private var label = JLabel("正在连接 ${config.username}@${config.host}:${config.port}...", SwingConstants.CENTER)
    private var statusLabel = JLabel()
    private var reconnectBtn = JButton("重新连接", AllIcons.Actions.Refresh)

    // 心跳保活
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private var heartbeatTask: ScheduledFuture<*>? = null

    @Volatile
    private var running = true
    
    @Volatile
    private var disconnected = false

    init {
        Disposer.register(parentDisposable, this)
        showConnecting()
        connect()
    }

    private fun showConnecting() {
        add(label, BorderLayout.CENTER)
    }

    private fun connect() {
        Thread {
            try {
                if (!connectionManager.connect(config)) {
                    showError("连接失败，请检查配置")
                    return@Thread
                }

                shellChannel = connectionManager.createShellChannel()
                if (shellChannel == null) {
                    showError("无法创建Shell通道")
                    return@Thread
                }
                
                val channel = shellChannel!!
                channel.open().verify(30, TimeUnit.SECONDS)

                val connector = SshTtyConnector(
                    channel.invertedOut,
                    channel.invertedIn,
                    channel
                )

                SwingUtilities.invokeLater {
                    try {
                        termWidget = object : JBTerminalWidget(project, JBTerminalSystemSettingsProvider(),parentDisposable) {
                            override fun createScrollBar(): JScrollBar {
                                return JBScrollBar()
                            }
                        }.apply {
                            ttyConnector = connector
                            preferredSize = Dimension(800, 600)
                        }
                        removeAll()
                        add(termWidget, BorderLayout.CENTER)
                        revalidate()
                        repaint()
                        termWidget?.start()
                        termWidget?.requestFocusInWindow()
                        
                        // 启动心跳保活
                        startHeartbeat()
                    } catch (e: Exception) {
                        showErrorDirect("终端初始化失败: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                label.text = "连接错误: ${e.message}"
                showError("连接错误: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun showError(message: String) {
        SwingUtilities.invokeLater {
            showErrorDirect(message)
        }
    }

    private fun showErrorDirect(message: String) {
        removeAll()
        add(JLabel("[错误] $message", SwingConstants.CENTER), BorderLayout.CENTER)
        revalidate()
        repaint()
    }
    
    /**
     * 显示断线提示，带重连按钮
     */
    private fun showDisconnected() {
        if (disconnected) return
        disconnected = true
        
        SwingUtilities.invokeLater {
            stopHeartbeat()
            
            // 在终端上方显示断线提示条
            val disconnectPanel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 5)).apply {
                background = java.awt.Color(255, 200, 200)
                add(JLabel("连接已断开").apply { icon = AllIcons.General.Warning })
                
                reconnectBtn.addActionListener { reconnect() }
                add(reconnectBtn)
            }
            
            add(disconnectPanel, BorderLayout.NORTH)
            revalidate()
            repaint()
        }
    }
    
    /**
     * 重新连接
     */
    private fun reconnect() {
        reconnectBtn.isEnabled = false
        reconnectBtn.text = "连接中..."
        
        Thread {
            try {
                // 关闭旧连接
                try {
                    termWidget?.stop()
                    shellChannel?.close()
                    connectionManager.close()
                } catch (_: Exception) {}
                
                // 重新创建连接
                connectionManager = SshConnectionManager()
                disconnected = false
                running = true
                
                SwingUtilities.invokeLater {
                    removeAll()
                    label.text = "正在重新连接..."
                    add(label, BorderLayout.CENTER)
                    revalidate()
                    repaint()
                }
                
                // 重新连接
                if (!connectionManager.connect(config)) {
                    showError("重新连接失败，请检查网络")
                    SwingUtilities.invokeLater {
                        reconnectBtn.isEnabled = true
                        reconnectBtn.text = "重新连接"
                    }
                    return@Thread
                }

                shellChannel = connectionManager.createShellChannel()
                if (shellChannel == null) {
                    showError("无法创建Shell通道")
                    SwingUtilities.invokeLater {
                        reconnectBtn.isEnabled = true
                        reconnectBtn.text = "重新连接"
                    }
                    return@Thread
                }
                
                val channel = shellChannel!!
                channel.open().verify(30, TimeUnit.SECONDS)

                val connector = SshTtyConnector(
                    channel.invertedOut,
                    channel.invertedIn,
                    channel
                )

                SwingUtilities.invokeLater {
                    try {
                        termWidget = object : JBTerminalWidget(project, JBTerminalSystemSettingsProvider(), parentDisposable) {
//                            override fun createScrollBar(): JScrollBar {
//                                return JBScrollBar()
//                            }
                        }.apply {
                            ttyConnector = connector
                            preferredSize = Dimension(800, 600)
                        }
                        removeAll()
                        add(termWidget, BorderLayout.CENTER)
                        revalidate()
                        repaint()
                        termWidget?.start()
                        termWidget?.requestFocusInWindow()
                        
                        startHeartbeat()
                        
                        reconnectBtn.isEnabled = true
                        reconnectBtn.text = "重新连接"
                    } catch (e: Exception) {
                        showErrorDirect("终端初始化失败: ${e.message}")
                        reconnectBtn.isEnabled = true
                        reconnectBtn.text = "重新连接"
                    }
                }
            } catch (e: Exception) {
                showError("重新连接错误: ${e.message}")
                SwingUtilities.invokeLater {
                    reconnectBtn.isEnabled = true
                    reconnectBtn.text = "重新连接"
                }
            }
        }.start()
    }
    
    /**
     * 启动心跳保活
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate({
            if (!running) return@scheduleAtFixedRate
            
            try {
                // 检查连接状态
                if (!connectionManager.isConnected() || shellChannel?.isOpen != true) {
                    showDisconnected()
                }
                shellChannel?.session?.sendIgnoreMessage(0)
            } catch (e: Exception) {
                showDisconnected()
            }
        }, 30, 30, TimeUnit.SECONDS)  // 每30秒检查一次
    }
    
    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
    }

    override fun dispose() {
        running = false
        stopHeartbeat()
        heartbeatExecutor.shutdownNow()
        try {
            termWidget?.stop()
            shellChannel?.close()
        } catch (_: Exception) {
        }
        connectionManager.close()
    }

    /**
     * SSH TTY连接器
     */
    private inner class SshTtyConnector(
        private val inputStream: InputStream,
        private val outputStream: OutputStream,
        private val channel: ChannelShell
    ) : TtyConnector {

        // 使用StreamDecoder处理UTF-8，能正确处理不完整的多字节序列
        private val reader = inputStream.bufferedReader(Charsets.UTF_8)

        override fun init(questioner: Questioner?): Boolean = true

        override fun read(buf: CharArray, offset: Int, length: Int): Int {
            return reader.read(buf, offset, length)
        }

        override fun write(bytes: ByteArray) {
            outputStream.write(bytes)
            outputStream.flush()
        }

        override fun write(string: String) {
            write(string.toByteArray(Charsets.UTF_8))
        }

        override fun isConnected(): Boolean = running && channel.isOpen

        override fun waitFor(): Int {
            return channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L).size
        }

        override fun ready(): Boolean {
            return try { 
                reader.ready()
            } catch (_: Exception) { 
                false 
            }
        }

        override fun getName(): String = "${config.username}@${config.host}"

        override fun close() {
            try { 
                reader.close()
                channel.close() 
            } catch (_: Exception) {}
        }
    }
}
