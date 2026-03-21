package com.lhstack.ssh.service

import com.lhstack.ssh.model.SshConfig
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.common.util.net.SshdSocketAddress
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
    private val sessionChain = mutableListOf<ClientSession>()
    private val forwardingTrackers = mutableListOf<ExplicitPortForwardingTracker>()

    @Volatile
    private var cancelled = false

    @Volatile
    var lastErrorMessage: String? = null
        private set

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
        if (cancelled) {
            lastErrorMessage = "连接已取消"
            return false
        }

        return try {
            disconnect()
            lastErrorMessage = null

            val chain = SshConnectionChainPlanner.buildChain(config)
            var connectHost = chain.first().host
            var connectPort = chain.first().port

            chain.forEachIndexed { index, endpoint ->
                val hopLabel = "第 ${index + 1}/${chain.size} 跳"
                validateEndpoint(endpoint, hopLabel)
                println("[SSH] $hopLabel 正在连接 ${endpoint.username}@${endpoint.host}:${endpoint.port}")

                val currentSession = connectSession(endpoint, connectHost, connectPort, hopLabel)
                    ?: run {
                        disconnect()
                        return false
                    }

                sessionChain.add(currentSession)
                session = currentSession

                if (index < chain.lastIndex) {
                    val next = chain[index + 1]
                    val tracker = try {
                        currentSession.createLocalPortForwardingTracker(
                            SshdSocketAddress(SshdSocketAddress.LOCALHOST_NAME, 0),
                            SshdSocketAddress(next.host, next.port)
                        )
                    } catch (e: Exception) {
                        disconnect()
                        return fail("第 ${index + 1}/${chain.size} 跳建立到 ${next.host}:${next.port} 转发", e)
                    }
                    forwardingTrackers.add(tracker)

                    val boundAddress = ForwardAddressResolver.resolveForward(
                        requested = tracker.localAddress,
                        bound = tracker.boundAddress
                    )
                    connectHost = boundAddress.host
                    connectPort = boundAddress.port
                    println("[SSH] $hopLabel 已建立到下一跳 ${next.host}:${next.port} 的转发，本地端口: $connectPort")
                }
            }

            if (session?.isAuthenticated == true) {
                true
            } else {
                fail("最终目标认证", IllegalStateException("会话未认证"))
            }
        } catch (e: Exception) {
            disconnect()
            fail("连接", e)
        }
    }

    private fun connectSession(
        endpoint: SshConnectionEndpoint,
        connectHost: String,
        connectPort: Int,
        hopLabel: String
    ): ClientSession? {
        val connectFuture = try {
            client.connect(endpoint.username, connectHost, connectPort).verify(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return failSession("$hopLabel 连接", e)
        }
        if (cancelled) return null

        val currentSession = connectFuture.session
        if (!connectFuture.isConnected || currentSession == null) {
            return failSession("$hopLabel 连接", IllegalStateException("连接未建立"))
        }
        println("[SSH] $hopLabel 连接建立，正在认证...")

        try {
            applyAuthentication(currentSession, endpoint, hopLabel)
        } catch (e: Exception) {
            currentSession.close()
            return failSession("$hopLabel 准备认证", e)
        }

        if (cancelled) return null

        val authFuture = try {
            currentSession.auth().verify(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            currentSession.close()
            return failSession("$hopLabel 认证", e)
        }

        val authenticated = authFuture.isSuccess && currentSession.isAuthenticated
        println("[SSH] $hopLabel 认证结果: $authenticated")
        if (!authenticated) {
            currentSession.close()
            return failSession("$hopLabel 认证", IllegalStateException("认证失败"))
        }

        try {
            org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_INTERVAL.set(
                currentSession,
                java.time.Duration.ofSeconds(30)
            )
            org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(currentSession, 3)
        } catch (e: Exception) {
            println("[SSH] $hopLabel 心跳配置失败（不影响连接）: ${e.message}")
        }
        return currentSession
    }

    private fun fail(stage: String, error: Throwable): Boolean {
        lastErrorMessage = SshConnectionErrorFormatter.format(stage, error)
        println("[SSH] $lastErrorMessage")
        error.printStackTrace()
        return false
    }

    private fun failSession(stage: String, error: Throwable): ClientSession? {
        lastErrorMessage = SshConnectionErrorFormatter.format(stage, error)
        println("[SSH] $lastErrorMessage")
        error.printStackTrace()
        return null
    }

    private fun applyAuthentication(currentSession: ClientSession, endpoint: SshConnectionEndpoint, hopLabel: String) {
        when (endpoint.authType) {
            SshConfig.AuthType.PASSWORD -> {
                println("[SSH] $hopLabel 使用密码认证")
                currentSession.addPasswordIdentity(endpoint.password)
            }

            SshConfig.AuthType.KEY -> {
                val privateKeyContent = if (endpoint.useLocalKey && SshConfig.hasLocalKey()) {
                    println("[SSH] $hopLabel 使用本地密钥认证 (~/.ssh/id_rsa)")
                    SshConfig.readLocalKey() ?: endpoint.privateKey
                } else {
                    println("[SSH] $hopLabel 使用配置的密钥认证")
                    endpoint.privateKey
                }

                if (privateKeyContent.isBlank()) {
                    throw IllegalArgumentException("$hopLabel 密钥内容为空")
                }

                val keyPair = loadKeyPair(privateKeyContent, endpoint.passphrase)
                currentSession.addPublicKeyIdentity(keyPair)
            }
        }
    }

    private fun validateEndpoint(endpoint: SshConnectionEndpoint, hopLabel: String) {
        require(endpoint.host.isNotBlank()) { "$hopLabel 主机不能为空" }
        require(endpoint.username.isNotBlank()) { "$hopLabel 用户名不能为空" }
        when (endpoint.authType) {
            SshConfig.AuthType.PASSWORD -> require(endpoint.password.isNotBlank()) { "$hopLabel 密码不能为空" }
            SshConfig.AuthType.KEY -> {
                if (endpoint.useLocalKey) {
                    require(SshConfig.hasLocalKey()) { "$hopLabel 本地密钥不存在" }
                } else {
                    require(endpoint.privateKey.isNotBlank()) { "$hopLabel 私钥不能为空" }
                }
            }
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
            forwardingTrackers.asReversed().forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            forwardingTrackers.clear()
            sessionChain.asReversed().forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            sessionChain.clear()
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
