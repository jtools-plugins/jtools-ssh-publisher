package com.lhstack.ssh.service

import com.lhstack.ssh.model.JumpHostConfig
import com.lhstack.ssh.model.SshConfig
import org.apache.sshd.common.util.net.SshdSocketAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JumpHostSupportTest {

    @Test
    fun jump_host_codec_round_trip_preserves_order_and_auth() {
        val jumpHosts = listOf(
            JumpHostConfig(
                host = "host1",
                port = 2201,
                username = "user1",
                authType = SshConfig.AuthType.PASSWORD,
                password = "secret-1"
            ),
            JumpHostConfig(
                host = "host2",
                port = 2202,
                username = "user2",
                authType = SshConfig.AuthType.KEY,
                privateKey = "PRIVATE-KEY",
                passphrase = "passphrase",
                useLocalKey = true
            )
        )

        val encoded = JumpHostCodec.encode(jumpHosts)
        val decoded = JumpHostCodec.decode(encoded)

        assertEquals(jumpHosts, decoded)
    }

    @Test
    fun jump_host_codec_returns_empty_for_blank_content() {
        assertTrue(JumpHostCodec.decode("").isEmpty())
        assertTrue(JumpHostCodec.decode("   ").isEmpty())
    }

    @Test
    fun existing_ssh_connection_is_copied_as_independent_jump_host_snapshot() {
        val source = SshConfig(
            host = "jump.example.com",
            port = 2202,
            username = "deploy",
            authType = SshConfig.AuthType.KEY,
            password = "unused-password",
            privateKey = "PRIVATE-KEY",
            passphrase = "key-passphrase",
            useLocalKey = true,
            jumpHosts = listOf(JumpHostConfig(host = "upstream-jump"))
        )

        val snapshot = JumpHostSnapshotFactory.from(source)

        assertEquals("jump.example.com", snapshot.host)
        assertEquals(2202, snapshot.port)
        assertEquals("deploy", snapshot.username)
        assertEquals(SshConfig.AuthType.KEY, snapshot.authType)
        assertEquals("unused-password", snapshot.password)
        assertEquals("PRIVATE-KEY", snapshot.privateKey)
        assertEquals("key-passphrase", snapshot.passphrase)
        assertTrue(snapshot.useLocalKey)
    }

    @Test
    fun connection_chain_includes_jump_hosts_before_target() {
        val target = SshConfig(
            host = "target-host",
            port = 22,
            username = "target-user",
            jumpHosts = listOf(
                JumpHostConfig(host = "jump-a", port = 2201, username = "user-a"),
                JumpHostConfig(host = "jump-b", port = 2202, username = "user-b")
            )
        )

        val chain = SshConnectionChainPlanner.buildChain(target)

        assertEquals(listOf("jump-a", "jump-b", "target-host"), chain.map { it.host })
        assertEquals(listOf(2201, 2202, 22), chain.map { it.port })
        assertEquals(listOf("user-a", "user-b", "target-user"), chain.map { it.username })
    }

    @Test
    fun local_forward_address_supports_inet_socket_address() {
        val address = InetSocketAddress("127.0.0.1", 10022)

        val resolved = ForwardAddressResolver.resolve(address)

        assertEquals("127.0.0.1", resolved.host)
        assertEquals(10022, resolved.port)
    }

    @Test
    fun local_forward_address_supports_sshd_socket_address() {
        val address = SshdSocketAddress("localhost", 10023)

        val resolved = ForwardAddressResolver.resolve(address)

        assertEquals("localhost", resolved.host)
        assertEquals(10023, resolved.port)
    }

    @Test
    fun local_forward_address_rejects_unknown_socket_address_type() {
        assertFailsWith<IllegalStateException> {
            ForwardAddressResolver.resolve(object : java.net.SocketAddress() {})
        }
    }

    @Test
    fun resolved_forward_address_prefers_bound_address_when_local_port_is_zero() {
        val resolved = ForwardAddressResolver.resolveForward(
            requested = SshdSocketAddress("localhost", 0),
            bound = SshdSocketAddress("127.0.0.1", 10024)
        )

        assertEquals("127.0.0.1", resolved.host)
        assertEquals(10024, resolved.port)
    }

    @Test
    fun connection_error_formatter_prefers_exception_message() {
        val message = SshConnectionErrorFormatter.format(
            stage = "第 2/2 跳认证",
            error = IllegalStateException("Auth fail")
        )

        assertEquals("第 2/2 跳认证失败: Auth fail", message)
    }

    @Test
    fun connection_error_formatter_falls_back_to_exception_type() {
        val message = SshConnectionErrorFormatter.format(
            stage = "第 1/2 跳连接",
            error = IllegalStateException("")
        )

        assertEquals("第 1/2 跳连接失败: IllegalStateException", message)
    }
}
