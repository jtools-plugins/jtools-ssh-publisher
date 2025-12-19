package com.lhstack.ssh.view

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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * SSH终端面板 - 使用JediTerm
 */
class SshTerminalPanel(
    val parentDisposable: Disposable,
    private val config: SshConfig,
    val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val connectionManager = SshConnectionManager()
    private var shellChannel: ChannelShell? = null
    private var termWidget: JediTermWidget? = null

    @Volatile
    private var running = true

    init {
        Disposer.register(parentDisposable, this)
        showConnecting()
        connect()
    }

    private fun showConnecting() {
        add(JLabel("正在连接 ${config.username}@${config.host}:${config.port}...", SwingConstants.CENTER), BorderLayout.CENTER)
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
                        termWidget = object: JBTerminalWidget(project,JBTerminalSystemSettingsProvider(),parentDisposable){
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
                    } catch (e: Exception) {
                        showErrorDirect("终端初始化失败: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
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

    override fun dispose() {
        running = false
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
