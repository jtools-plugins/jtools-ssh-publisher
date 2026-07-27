package com.lhstack.ssh.model

import java.io.File
import java.io.Serializable
import java.util.UUID

/**
 * SSH连接配置
 */
data class SshConfig(
    var id: String = UUID.randomUUID().toString().replace("-",""),
    /** 关联 SshGroup.id；空串表示未分组（显示为"默认"） */
    var groupId: String = "",
    var name: String = "",
    var host: String = "127.0.0.1",
    var port: Int = 22,
    var username: String = "root",
    var authType: AuthType = AuthType.PASSWORD,
    var password: String = "",
    var privateKey: String = "",
    var passphrase: String = "",
    var remoteDir: String = "/tmp",
    var useLocalKey: Boolean = false,
    var jumpHosts: List<JumpHostConfig> = emptyList(),
    /** 分组内排序，越小越靠前 */
    var sortOrder: Int = 0
) : Serializable {
    enum class AuthType {
        PASSWORD, KEY
    }
    
    companion object {
        /**
         * 获取本地默认私钥路径
         */
        fun getLocalKeyPath(): File {
            return File(System.getProperty("user.home"), ".ssh/id_rsa")
        }
        
        /**
         * 读取本地默认私钥内容
         */
        fun readLocalKey(): String? {
            val keyFile = getLocalKeyPath()
            return if (keyFile.exists() && keyFile.isFile) {
                keyFile.readText()
            } else {
                null
            }
        }
        
        /**
         * 检查本地密钥是否存在
         */
        fun hasLocalKey(): Boolean {
            return getLocalKeyPath().exists()
        }
    }
}

data class JumpHostConfig(
    var host: String = "",
    var port: Int = 22,
    var username: String = "root",
    var authType: SshConfig.AuthType = SshConfig.AuthType.PASSWORD,
    var password: String = "",
    var privateKey: String = "",
    var passphrase: String = "",
    var useLocalKey: Boolean = false
) : Serializable

/**
 * 脚本配置
 */
data class ScriptConfig(
    var id: String = System.currentTimeMillis().toString(),
    var sshConfigId: String = "",
    var name: String = "",
    var scriptType: ScriptType = ScriptType.PRE,
    var shellType: ShellType = ShellType.DEFAULT,
    var content: String = "",
    var enabled: Boolean = true
) : Serializable {
    enum class ScriptType {
        /** 远程前置脚本（SSH 执行） */
        PRE,
        /** 远程后置脚本（SSH 执行） */
        POST,
        /** 本地前置脚本（本机 ProcessBuilder 执行） */
        LOCAL_PRE,
        /** 本地后置脚本（本机 ProcessBuilder 执行） */
        LOCAL_POST;

        val isLocal: Boolean get() = this == LOCAL_PRE || this == LOCAL_POST
    }

    /**
     * 本地脚本使用的 Shell 类型，仅对 LOCAL_PRE / LOCAL_POST 生效。
     * DEFAULT 在 macOS/Linux 使用 /bin/sh，在 Windows 使用 cmd。
     */
    enum class ShellType(val label: String) {
        DEFAULT("默认（系统 sh / cmd）"),
        BASH("Bash"),
        ZSH("Zsh"),
        SH("sh"),
        CMD("Windows cmd"),
        POWERSHELL("PowerShell")
    }
}

/**
 * 上传任务模板
 */
data class UploadTemplate(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var group: String = "",
    var name: String = "",
    var localPath: String = "",           // 本地文件路径
    var sshConfigId: String = "",         // 目标SSH配置ID
    var remotePath: String = "/tmp",      // 远程目录
    var remoteFileName: String = "",      // 远程文件名（空则使用原文件名）
    var preScript: String = "",           // 临时前置脚本
    var postScript: String = "",          // 临时后置脚本
    var preScriptIds: List<String> = emptyList(),   // 选中的前置脚本ID
    var postScriptIds: List<String> = emptyList(),  // 选中的后置脚本ID
    var createTime: Long = System.currentTimeMillis()
) : Serializable
