package com.lhstack.ssh.service

import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * SSH配置服务 - 使用SQLite存储
 */
object SshConfigService {

    private val dbPath: String by lazy {
        val dir = File(System.getProperty("user.home"), ".jtools/jtools-ssh-publisher")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "db.data").absolutePath
    }

    private val connection: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also { initTable(it) }
    }

    private fun initTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ssh_config (
                    id TEXT PRIMARY KEY,
                    group_name TEXT,
                    name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER DEFAULT 22,
                    username TEXT NOT NULL,
                    auth_type TEXT DEFAULT 'PASSWORD',
                    password TEXT,
                    private_key TEXT,
                    passphrase TEXT,
                    remote_dir TEXT DEFAULT '/tmp',
                    use_local_key INTEGER DEFAULT 0
                )
            """.trimIndent()
            )

            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS script_config (
                    id TEXT PRIMARY KEY,
                    ssh_config_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    script_type TEXT DEFAULT 'PRE',
                    content TEXT,
                    enabled INTEGER DEFAULT 1,
                    FOREIGN KEY (ssh_config_id) REFERENCES ssh_config(id) ON DELETE CASCADE
                )
            """.trimIndent()
            )
            
            // 迁移：添加 use_local_key 列（如果不存在）
            try {
                stmt.executeUpdate("ALTER TABLE ssh_config ADD COLUMN use_local_key INTEGER DEFAULT 0")
            } catch (_: Exception) {
                // 列已存在，忽略
            }
        }
    }

    fun getConfigs(): List<SshConfig> {
        val list = mutableListOf<SshConfig>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM ssh_config ORDER BY group_name, name").use { rs ->
                while (rs.next()) {
                    list.add(
                        SshConfig(
                            id = rs.getString("id"),
                            group = rs.getString("group_name") ?: "",
                            name = rs.getString("name"),
                            host = rs.getString("host"),
                            port = rs.getInt("port"),
                            username = rs.getString("username"),
                            authType = SshConfig.AuthType.valueOf(rs.getString("auth_type") ?: "PASSWORD"),
                            password = rs.getString("password") ?: "",
                            privateKey = rs.getString("private_key") ?: "",
                            passphrase = rs.getString("passphrase") ?: "",
                            remoteDir = rs.getString("remote_dir") ?: "/tmp",
                            useLocalKey = try { rs.getInt("use_local_key") == 1 } catch (_: Exception) { false }
                        )
                    )
                }
            }
        }
        return list
    }

    fun addConfig(config: SshConfig) {
        connection.prepareStatement(
            """
            INSERT INTO ssh_config (id, group_name, name, host, port, username, auth_type, 
                password, private_key, passphrase, remote_dir, use_local_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, config.id)
            stmt.setString(2, config.group)
            stmt.setString(3, config.name)
            stmt.setString(4, config.host)
            stmt.setInt(5, config.port)
            stmt.setString(6, config.username)
            stmt.setString(7, config.authType.name)
            stmt.setString(8, config.password)
            stmt.setString(9, config.privateKey)
            stmt.setString(10, config.passphrase)
            stmt.setString(11, config.remoteDir)
            stmt.setInt(12, if (config.useLocalKey) 1 else 0)
            stmt.executeUpdate()
        }
    }

    fun updateConfig(config: SshConfig) {
        connection.prepareStatement(
            """
            UPDATE ssh_config SET group_name=?, name=?, host=?, port=?, username=?, auth_type=?,
                password=?, private_key=?, passphrase=?, remote_dir=?, use_local_key=?
            WHERE id=?
        """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, config.group)
            stmt.setString(2, config.name)
            stmt.setString(3, config.host)
            stmt.setInt(4, config.port)
            stmt.setString(5, config.username)
            stmt.setString(6, config.authType.name)
            stmt.setString(7, config.password)
            stmt.setString(8, config.privateKey)
            stmt.setString(9, config.passphrase)
            stmt.setString(10, config.remoteDir)
            stmt.setInt(11, if (config.useLocalKey) 1 else 0)
            stmt.setString(12, config.id)
            stmt.executeUpdate()
        }
    }

    fun removeConfig(id: String) {
        // 先删除关联的脚本
        connection.prepareStatement("DELETE FROM script_config WHERE ssh_config_id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM ssh_config WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    fun getConfigById(id: String): SshConfig? {
        connection.prepareStatement("SELECT * FROM ssh_config WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return SshConfig(
                        id = rs.getString("id"),
                        group = rs.getString("group_name") ?: "",
                        name = rs.getString("name"),
                        host = rs.getString("host"),
                        port = rs.getInt("port"),
                        username = rs.getString("username"),
                        authType = SshConfig.AuthType.valueOf(rs.getString("auth_type") ?: "PASSWORD"),
                        password = rs.getString("password") ?: "",
                        privateKey = rs.getString("private_key") ?: "",
                        passphrase = rs.getString("passphrase") ?: "",
                        remoteDir = rs.getString("remote_dir") ?: "/tmp",
                        useLocalKey = try { rs.getInt("use_local_key") == 1 } catch (_: Exception) { false }
                    )
                }
            }
        }
        return null
    }

    fun getConfigsByGroup(): Map<String, List<SshConfig>> {
        return getConfigs().groupBy { it.group.ifEmpty { "默认" } }
    }

    /**
     * 检查 group+name 是否已存在（排除指定 id）
     */
    fun existsByGroupAndName(group: String, name: String, excludeId: String? = null): Boolean {
        val sql = if (excludeId != null) {
            "SELECT 1 FROM ssh_config WHERE group_name=? AND name=? AND id<>?"
        } else {
            "SELECT 1 FROM ssh_config WHERE group_name=? AND name=?"
        }
        println("[DEBUG] existsByGroupAndName: group=$group, name=$name, excludeId=$excludeId, sql=$sql")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, group)
            stmt.setString(2, name)
            if (excludeId != null) {
                stmt.setString(3, excludeId)
            }
            stmt.executeQuery().use { rs ->
                val exists = rs.next()
                println("[DEBUG] existsByGroupAndName result: $exists")
                return exists
            }
        }
    }

    // ========== 脚本管理 ==========

    fun getScriptsByConfigId(sshConfigId: String): List<ScriptConfig> {
        val list = mutableListOf<ScriptConfig>()
        connection.prepareStatement("SELECT * FROM script_config WHERE ssh_config_id=? ORDER BY script_type, name")
            .use { stmt ->
                stmt.setString(1, sshConfigId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(
                            ScriptConfig(
                                id = rs.getString("id"),
                                sshConfigId = rs.getString("ssh_config_id"),
                                name = rs.getString("name"),
                                scriptType = ScriptConfig.ScriptType.valueOf(rs.getString("script_type") ?: "PRE"),
                                content = rs.getString("content") ?: "",
                                enabled = rs.getInt("enabled") == 1
                            )
                        )
                    }
                }
            }
        return list
    }

    fun getPreScripts(sshConfigId: String): List<ScriptConfig> {
        return getScriptsByConfigId(sshConfigId).filter { it.scriptType == ScriptConfig.ScriptType.PRE }
    }

    fun getPostScripts(sshConfigId: String): List<ScriptConfig> {
        return getScriptsByConfigId(sshConfigId).filter { it.scriptType == ScriptConfig.ScriptType.POST }
    }

    fun addScript(script: ScriptConfig) {
        connection.prepareStatement(
            """
            INSERT INTO script_config (id, ssh_config_id, name, script_type, content, enabled)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, script.id)
            stmt.setString(2, script.sshConfigId)
            stmt.setString(3, script.name)
            stmt.setString(4, script.scriptType.name)
            stmt.setString(5, script.content)
            stmt.setInt(6, if (script.enabled) 1 else 0)
            stmt.executeUpdate()
        }
    }

    fun updateScript(script: ScriptConfig) {
        connection.prepareStatement(
            """
            UPDATE script_config SET name=?, script_type=?, content=?, enabled=?
            WHERE id=?
        """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, script.name)
            stmt.setString(2, script.scriptType.name)
            stmt.setString(3, script.content)
            stmt.setInt(4, if (script.enabled) 1 else 0)
            stmt.setString(5, script.id)
            stmt.executeUpdate()
        }
    }

    fun removeScript(id: String) {
        connection.prepareStatement("DELETE FROM script_config WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    fun close() {
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}
