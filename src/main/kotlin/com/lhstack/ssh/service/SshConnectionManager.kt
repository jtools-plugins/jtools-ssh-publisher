package com.lhstack.ssh.service

import com.lhstack.ssh.model.SshConfig
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.concurrent.TimeUnit

/**
 * SSH连接管理器
 */
class SshConnectionManager {

    private val client: SshClient = SshClient.setUpDefaultClient().apply {
        start()
    }
    private var session: ClientSession? = null
    private var sftpClient: SftpClient? = null

    @Volatile
    private var cancelled = false

    /**
     * 取消当前操作
     */
    fun cancel() {
        cancelled = true
        disconnect()
    }

    /**
     * 连接SSH服务器
     */
    fun connect(config: SshConfig): Boolean {
        if (cancelled) return false

        return try {
            disconnect()

            println("[SSH] 正在连接 ${config.username}@${config.host}:${config.port}")

            val connectFuture = client.connect(config.username, config.host, config.port)
            if (!connectFuture.await(15, TimeUnit.SECONDS)) {
                println("[SSH] 连接超时")
                return false
            }
            if (cancelled) return false

            session = connectFuture.session

            println("[SSH] 连接建立，正在认证...")

            when (config.authType) {
                SshConfig.AuthType.PASSWORD -> {
                    println("[SSH] 使用密码认证")
                    session?.addPasswordIdentity(config.password)
                }

                SshConfig.AuthType.KEY -> {
                    println("[SSH] 使用密钥认证")
                    val keyPair = loadKeyPair(config.privateKey, config.passphrase)
                    session?.addPublicKeyIdentity(keyPair)
                }
            }

            if (cancelled) return false

            val authFuture = session?.auth()
            if (authFuture?.await(15, TimeUnit.SECONDS) != true) {
                println("[SSH] 认证超时")
                return false
            }

            val authenticated = session?.isAuthenticated == true
            println("[SSH] 认证结果: $authenticated")

            if (authenticated) {
                try {
                    org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_INTERVAL.set(
                        session,
                        java.time.Duration.ofSeconds(30)
                    )
                    org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(session, 3)
                    println("[SSH] 心跳保活已配置")
                } catch (e: Exception) {
                    println("[SSH] 心跳配置失败（不影响连接）: ${e.message}")
                }
            }

            authenticated
        } catch (e: Exception) {
            println("[SSH] 连接错误: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 加载密钥对
     */
    private fun loadKeyPair(privateKeyContent: String, passphrase: String): KeyPair {
        val keyPairs = org.apache.sshd.common.util.security.SecurityUtils.loadKeyPairIdentities(
            null,
            { "key" },
            ByteArrayInputStream(privateKeyContent.toByteArray(StandardCharsets.UTF_8)),
            org.apache.sshd.common.config.keys.FilePasswordProvider { _, _, _ -> passphrase }
        )
        
        return keyPairs.firstOrNull() ?: throw IllegalArgumentException("无法加载密钥")
    }
    
    /**
     * 执行命令
     */
    fun executeCommand(command: String): String {
        val currentSession = session ?: throw IllegalStateException("未连接")
        
        return currentSession.createExecChannel(command).use { channel ->
            channel.open().verify(30, TimeUnit.SECONDS)
            val output = channel.invertedOut.bufferedReader().readText()
            val error = channel.invertedErr.bufferedReader().readText()
            if (error.isNotEmpty()) "$output\n$error" else output
        }
    }
    
    /**
     * 获取SFTP客户端
     */
    fun getSftpClient(): SftpClient {
        val currentSession = session ?: throw IllegalStateException("未连接")
        if (sftpClient == null) {
            sftpClient = SftpClientFactory.instance().createSftpClient(currentSession)
        }
        return sftpClient!!
    }
    
    /**
     * 上传文件
     */
    fun uploadFile(localFile: File, remotePath: String, progressCallback: ((Long, Long) -> Unit)? = null): Boolean {
        return try {
            val sftp = getSftpClient()
            val totalSize = localFile.length()
            var uploaded = 0L
            
            sftp.write(remotePath).use { output ->
                localFile.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        uploaded += read
                        progressCallback?.invoke(uploaded, totalSize)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 创建Shell通道
     */
    fun createShellChannel(): ChannelShell? {
        val currentSession = session ?: run {
            println("[SSH] 无法创建Shell通道：session为空")
            return null
        }
        
        return try {
            println("[SSH] 正在创建Shell通道...")
            currentSession.createShellChannel().apply {
                ptyType = "xterm-256color"
                ptyColumns = 120
                ptyLines = 40
                // 设置UTF-8编码环境变量
                setEnv("LANG", "en_US.UTF-8")
                println("[SSH] Shell通道创建成功")
            }
        } catch (e: Exception) {
            println("[SSH] 创建Shell通道失败: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            sftpClient?.close()
            sftpClient = null
            session?.close()
            session = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = session?.isOpen == true && session?.isAuthenticated == true
    
    /**
     * 关闭客户端
     */
    fun close() {
        disconnect()
        client.stop()
    }
}
