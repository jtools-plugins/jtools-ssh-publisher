package com.lhstack.ssh.model

import java.io.Serializable

/**
 * SSH连接配置
 */
data class SshConfig(
    var id: String = System.currentTimeMillis().toString(),
    var group: String = "",
    var name: String = "",
    var host: String = "127.0.0.1",
    var port: Int = 22,
    var username: String = "root",
    var authType: AuthType = AuthType.PASSWORD,
    var password: String = "",
    var privateKey: String = "",
    var passphrase: String = "",
    var remoteDir: String = "/tmp"
) : Serializable {
    enum class AuthType {
        PASSWORD, KEY
    }
}

/**
 * 脚本配置
 */
data class ScriptConfig(
    var id: String = System.currentTimeMillis().toString(),
    var sshConfigId: String = "",
    var name: String = "",
    var scriptType: ScriptType = ScriptType.PRE,
    var content: String = "",
    var enabled: Boolean = true
) : Serializable {
    enum class ScriptType {
        PRE, POST
    }
}
