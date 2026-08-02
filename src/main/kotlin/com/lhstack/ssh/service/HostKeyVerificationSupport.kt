package com.lhstack.ssh.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import java.awt.Component
import java.io.File
import java.net.SocketAddress
import java.security.PublicKey

/**
 * 已信任主机指纹存储。
 *
 * 使用自有格式（每行 host\tport\tfingerprint），而非 openssh known_hosts 格式：
 * - 跳板链的中间跳底层连的是本地转发端口（127.0.0.1:随机端口），无法用 socket 地址稳定标识目标主机，
 *   因此这里以每一跳配置中的真实 host:port 作为标识。
 * - 指纹采用 MINA sshd 的 SHA256 指纹，仅存于用户本机，不经网络传输。
 */
object KnownHostsStore {

    private val storeFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".jtools/jtools-ssh-publisher")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "known_hosts")
    }

    private val lock = Any()

    /**
     * 查询已保存的指纹；无记录返回 null。
     */
    fun getFingerprint(host: String, port: Int): String? {
        synchronized(lock) {
            if (!storeFile.exists()) return null
            val key = entryKey(host, port)
            return storeFile.readLines()
                .asSequence()
                .mapNotNull { parseLine(it) }
                .firstOrNull { it.first == key }
                ?.second
        }
    }

    /**
     * 保存或更新主机指纹。
     */
    fun saveFingerprint(host: String, port: Int, fingerprint: String) {
        synchronized(lock) {
            val key = entryKey(host, port)
            val lines = if (storeFile.exists()) storeFile.readLines().toMutableList() else mutableListOf()
            val kept = lines.filter { parseLine(it)?.first != key }
            val updated = kept + "$key\t$fingerprint"
            storeFile.writeText(updated.joinToString(System.lineSeparator()))
        }
    }

    private fun entryKey(host: String, port: Int) = "$host\t$port"

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val parts = trimmed.split("\t")
        if (parts.size < 3) return null
        return "${parts[0]}\t${parts[1]}" to parts[2]
    }
}

/**
 * 服务器主机密钥校验器。
 *
 * 校验以配置中的真实目标 host:port 为标识（通过 [expect] 在每一跳连接前设置），
 * 避免跳板链中间跳因本地转发地址导致标识不稳定。
 *
 * - 未记录过该主机：弹出指纹确认框，用户确认后保存并接受，取消则拒绝。
 * - 已记录且指纹一致：直接接受。
 * - 已记录但指纹不一致：弹出高危警告，用户显式确认后更新并接受，否则拒绝。
 */
class HostKeyVerifier(
    private val dialogParent: Component? = null,
    private val dialogModalityState: ModalityState? = null
) : ServerKeyVerifier {

    @Volatile
    private var expectedHost: String = ""

    @Volatile
    private var expectedPort: Int = 22

    @Volatile
    private var expectedLabel: String = ""

    /**
     * 记录当前正在连接的真实目标，用于指纹校验的标识与提示文案。
     */
    fun expect(host: String, port: Int, label: String) {
        expectedHost = host
        expectedPort = port
        expectedLabel = label
    }

    override fun verifyServerKey(session: ClientSession, remoteAddress: SocketAddress, serverKey: PublicKey): Boolean {
        val host = expectedHost
        val port = expectedPort
        val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
        val saved = KnownHostsStore.getFingerprint(host, port)

        return when {
            saved == null -> confirmUnknownHost(host, port, fingerprint)
            saved == fingerprint -> true
            else -> confirmModifiedHost(host, port, saved, fingerprint)
        }
    }

    private fun confirmUnknownHost(host: String, port: Int, fingerprint: String): Boolean {
        val accepted = askOnUi(
            title = "确认主机指纹",
            message = buildString {
                append("首次连接 $expectedLabel $host:$port。")
                appendLine()
                appendLine()
                append("主机密钥指纹 (SHA256):")
                appendLine()
                append(fingerprint)
                appendLine()
                appendLine()
                append("请确认该指纹与目标服务器一致后再信任。是否信任并保存？")
            },
            warning = false
        )
        if (accepted) {
            KnownHostsStore.saveFingerprint(host, port, fingerprint)
        }
        return accepted
    }

    private fun confirmModifiedHost(host: String, port: Int, saved: String, current: String): Boolean {
        val accepted = askOnUi(
            title = "主机指纹已变化",
            message = buildString {
                append("警告：$expectedLabel $host:$port 的主机密钥指纹与已保存记录不一致，可能存在中间人攻击风险！")
                appendLine()
                appendLine()
                append("已保存 (SHA256): $saved")
                appendLine()
                append("当前 (SHA256): $current")
                appendLine()
                appendLine()
                append("仅在你确认服务器密钥确实已变更时才应继续。是否更新并信任新指纹？")
            },
            warning = true
        )
        if (accepted) {
            KnownHostsStore.saveFingerprint(host, port, current)
        }
        return accepted
    }

    private fun askOnUi(title: String, message: String, warning: Boolean): Boolean {
        var accepted = false
        val app = ApplicationManager.getApplication()
        val ask = Runnable {
            val icon = if (warning) Messages.getWarningIcon() else Messages.getQuestionIcon()
            val result = if (dialogParent != null) {
                Messages.showDialog(
                    dialogParent,
                    message,
                    title,
                    arrayOf("信任", "取消"),
                    0,
                    icon
                )
            } else {
                Messages.showYesNoDialog(
                    message,
                    title,
                    "信任",
                    "取消",
                    icon
                )
            }
            accepted = result == Messages.YES
        }
        if (app != null) {
            val modalityState = dialogModalityState
            if (modalityState != null) {
                app.invokeAndWait(ask, modalityState)
            } else {
                app.invokeAndWait(ask)
            }
        } else {
            ask.run()
        }
        return accepted
    }
}
