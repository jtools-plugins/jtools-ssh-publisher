package com.lhstack.ssh.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lhstack.ssh.model.JumpHostConfig
import com.lhstack.ssh.model.SshConfig
import org.apache.sshd.common.util.net.SshdSocketAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

data class SshConnectionEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshConfig.AuthType,
    val password: String,
    val privateKey: String,
    val passphrase: String,
    val useLocalKey: Boolean,
    val isTarget: Boolean
)

data class ForwardAddress(
    val host: String,
    val port: Int
)

object JumpHostCodec {
    private val gson = Gson()
    private val listType = object : TypeToken<List<JumpHostConfig>>() {}.type

    fun encode(jumpHosts: List<JumpHostConfig>): String {
        if (jumpHosts.isEmpty()) {
            return "[]"
        }
        return gson.toJson(jumpHosts, listType)
    }

    fun decode(content: String?): List<JumpHostConfig> {
        if (content.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson<List<JumpHostConfig>>(content, listType) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}

object JumpHostSnapshotFactory {
    /**
     * 将已有 SSH 连接复制为独立的跳板机快照。
     *
     * 只复制连接本身的端点与认证参数，不复制该连接已有的跳板链；后续修改或删除
     * 原 SSH 连接不会隐式改变已保存的跳板链。
     */
    fun from(config: SshConfig): JumpHostConfig = JumpHostConfig(
        host = config.host,
        port = config.port,
        username = config.username,
        authType = config.authType,
        password = config.password,
        privateKey = config.privateKey,
        passphrase = config.passphrase,
        useLocalKey = config.useLocalKey
    )
}

object SshConnectionChainPlanner {
    private const val MAX_JUMP_HOSTS = 16

    fun buildChain(target: SshConfig): List<SshConnectionEndpoint> {
        require(target.jumpHosts.size <= MAX_JUMP_HOSTS) {
            "跳板链长度不能超过 $MAX_JUMP_HOSTS"
        }

        val jumpEndpoints = target.jumpHosts.map {
            SshConnectionEndpoint(
                host = it.host,
                port = it.port,
                username = it.username,
                authType = it.authType,
                password = it.password,
                privateKey = it.privateKey,
                passphrase = it.passphrase,
                useLocalKey = it.useLocalKey,
                isTarget = false
            )
        }

        return jumpEndpoints + SshConnectionEndpoint(
            host = target.host,
            port = target.port,
            username = target.username,
            authType = target.authType,
            password = target.password,
            privateKey = target.privateKey,
            passphrase = target.passphrase,
            useLocalKey = target.useLocalKey,
            isTarget = true
        )
    }
}

object ForwardAddressResolver {
    fun resolve(address: SocketAddress): ForwardAddress {
        return when (address) {
            is InetSocketAddress -> ForwardAddress(address.hostString, address.port)
            is SshdSocketAddress -> ForwardAddress(address.hostName, address.port)
            else -> throw IllegalStateException("不支持的本地转发地址类型: ${address::class.java.name}")
        }
    }

    fun resolveForward(requested: SocketAddress, bound: SocketAddress): ForwardAddress {
        val requestedAddress = resolve(requested)
        val boundAddress = resolve(bound)
        return if (requestedAddress.port == 0) boundAddress else requestedAddress
    }
}

object SshConnectionErrorFormatter {
    fun format(stage: String, error: Throwable): String {
        val detail = error.message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error.cause?.message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error::class.java.simpleName
        return "${stage}失败: $detail"
    }
}
