package com.lhstack.ssh.service

import com.lhstack.ssh.model.AnsibleGroupScript
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.SshGroup
import com.lhstack.ssh.model.UploadTemplate
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

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
            // ===== 基础表 =====
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ssh_group (
                    id   TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE
                )
            """.trimIndent())

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ssh_config (
                    id TEXT PRIMARY KEY,
                    group_id TEXT DEFAULT '',
                    name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER DEFAULT 22,
                    username TEXT NOT NULL,
                    auth_type TEXT DEFAULT 'PASSWORD',
                    password TEXT,
                    private_key TEXT,
                    passphrase TEXT,
                    remote_dir TEXT DEFAULT '/tmp',
                    use_local_key INTEGER DEFAULT 0,
                    jump_hosts_json TEXT DEFAULT '[]'
                )
            """.trimIndent())

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS script_config (
                    id TEXT PRIMARY KEY,
                    ssh_config_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    script_type TEXT DEFAULT 'PRE',
                    shell_type TEXT DEFAULT 'DEFAULT',
                    content TEXT,
                    enabled INTEGER DEFAULT 1,
                    FOREIGN KEY (ssh_config_id) REFERENCES ssh_config(id) ON DELETE CASCADE
                )
            """.trimIndent())

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS upload_template (
                    id TEXT PRIMARY KEY,
                    group_name TEXT,
                    name TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    ssh_config_id TEXT NOT NULL,
                    remote_path TEXT DEFAULT '/tmp',
                    remote_file_name TEXT,
                    pre_script TEXT,
                    post_script TEXT,
                    pre_script_ids TEXT,
                    post_script_ids TEXT,
                    create_time INTEGER
                )
            """.trimIndent())

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ansible_group_script (
                    id TEXT PRIMARY KEY,
                    group_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT,
                    create_time INTEGER,
                    sort_order INTEGER DEFAULT 0,
                    FOREIGN KEY (group_id) REFERENCES ssh_group(id) ON DELETE CASCADE
                )
            """.trimIndent())

            // ===== 幂等迁移 =====
            // 旧 ssh_config.group_name -> 迁移到 ssh_group + ssh_config.group_id
            migrateGroupNameToGroupId(conn)

            // 幽灵迁移 + sort_order 列迁移
            listOf(
                "ALTER TABLE ssh_config ADD COLUMN use_local_key INTEGER DEFAULT 0",
                "ALTER TABLE ssh_config ADD COLUMN jump_hosts_json TEXT DEFAULT '[]'",
                "ALTER TABLE ssh_config ADD COLUMN group_id TEXT DEFAULT ''",
                "ALTER TABLE ssh_config ADD COLUMN sort_order INTEGER DEFAULT 0",
                "ALTER TABLE script_config ADD COLUMN shell_type TEXT DEFAULT 'DEFAULT'",
                "ALTER TABLE upload_template ADD COLUMN pre_script_ids TEXT",
                "ALTER TABLE upload_template ADD COLUMN post_script_ids TEXT",
                "ALTER TABLE ansible_group_script ADD COLUMN sort_order INTEGER DEFAULT 0"
            ).forEach { sql ->
                try { stmt.executeUpdate(sql) } catch (_: Exception) {}
            }

            // 删除 ssh_config 中旧的 group_name 列（SQLite 3.35+）
            try { stmt.executeUpdate("ALTER TABLE ssh_config DROP COLUMN group_name") } catch (_: Exception) {}
        }
    }

    /**
     * 将旧 group_name 列迁移到独立的 ssh_group 表。
     * 幂等：若 ssh_group 表已有数据，则跳过；若 group_id 列已有值，则跳过。
     */
    @Synchronized
    private fun migrateGroupNameToGroupId(conn: Connection) {
        // 检查 group_name 列是否存在
        val hasGroupName = try {
            conn.createStatement().use { s ->
                s.executeQuery("SELECT group_name FROM ssh_config LIMIT 1").close()
                true
            }
        } catch (_: Exception) { false }

        if (!hasGroupName) return

        // 检查是否已迁移（group_id 已有非空值则跳过）
        val alreadyMigrated = try {
            conn.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM ssh_config WHERE group_id IS NOT NULL AND group_id != ''").use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        } catch (_: Exception) { false }

        if (alreadyMigrated) return

        // 读取所有不同的 group_name
        val groupNames = mutableSetOf<String>()
        try {
            conn.createStatement().use { s ->
                s.executeQuery("SELECT DISTINCT group_name FROM ssh_config WHERE group_name IS NOT NULL AND group_name != ''")
                    .use { rs -> while (rs.next()) groupNames.add(rs.getString(1)) }
            }
        } catch (_: Exception) { return }

        // 确保 group_id 列存在
        try { conn.createStatement().use { it.executeUpdate("ALTER TABLE ssh_config ADD COLUMN group_id TEXT DEFAULT ''") } } catch (_: Exception) {}

        // 创建分组记录并回填 group_id
        groupNames.forEach { groupName ->
            val groupId = UUID.randomUUID().toString().replace("-", "")
            try {
                conn.prepareStatement("INSERT OR IGNORE INTO ssh_group (id, name) VALUES (?, ?)").use { s ->
                    s.setString(1, groupId); s.setString(2, groupName); s.executeUpdate()
                }
            } catch (_: Exception) {}

            // 查实际插入的 id（INSERT OR IGNORE 可能已存在）
            val actualId = conn.prepareStatement("SELECT id FROM ssh_group WHERE name=?").use { s ->
                s.setString(1, groupName)
                s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else groupId }
            }

            try {
                conn.prepareStatement("UPDATE ssh_config SET group_id=? WHERE group_name=?").use { s ->
                    s.setString(1, actualId); s.setString(2, groupName); s.executeUpdate()
                }
            } catch (_: Exception) {}
        }
    }

    // ========== 分组管理 ==========

    @Synchronized
    fun getGroups(): List<SshGroup> {
        val list = mutableListOf<SshGroup>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM ssh_group ORDER BY name").use { rs ->
                while (rs.next()) list.add(SshGroup(id = rs.getString("id"), name = rs.getString("name")))
            }
        }
        return list
    }

    @Synchronized
    fun addGroup(group: SshGroup) {
        connection.prepareStatement("INSERT OR IGNORE INTO ssh_group (id, name) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, group.id); stmt.setString(2, group.name); stmt.executeUpdate()
        }
    }

    @Synchronized
    fun renameGroup(groupId: String, newName: String) {
        connection.prepareStatement("UPDATE ssh_group SET name=? WHERE id=?").use { stmt ->
            stmt.setString(1, newName); stmt.setString(2, groupId); stmt.executeUpdate()
        }
    }

    /**
     * 删除分组：关联的 ssh_config.group_id 置空，ansible_group_script 级联删除。
     */
    @Synchronized
    fun deleteGroup(groupId: String) {
        connection.prepareStatement("UPDATE ssh_config SET group_id='' WHERE group_id=?").use { stmt ->
            stmt.setString(1, groupId); stmt.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM ssh_group WHERE id=?").use { stmt ->
            stmt.setString(1, groupId); stmt.executeUpdate()
        }
    }

    fun getGroupById(id: String): SshGroup? {
        connection.prepareStatement("SELECT * FROM ssh_group WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return SshGroup(id = rs.getString("id"), name = rs.getString("name"))
            }
        }
        return null
    }

    /** 分组名 -> SshGroup，不存在则新建 */
    @Synchronized
    fun getOrCreateGroupByName(name: String): SshGroup {
        val trimmed = name.trim().ifEmpty { "默认" }
        val existing = connection.prepareStatement("SELECT * FROM ssh_group WHERE name=?").use { stmt ->
            stmt.setString(1, trimmed)
            stmt.executeQuery().use { rs ->
                if (rs.next()) SshGroup(id = rs.getString("id"), name = rs.getString("name")) else null
            }
        }
        if (existing != null) return existing
        val group = SshGroup(name = trimmed)
        addGroup(group)
        return group
    }

    // ========== SSH 配置管理 ==========

    @Synchronized
    fun getConfigs(): List<SshConfig> {
        val list = mutableListOf<SshConfig>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM ssh_config ORDER BY group_id, sort_order, name").use { rs ->
                while (rs.next()) {
                    list.add(SshConfig(
                        id = rs.getString("id"),
                        groupId = try { rs.getString("group_id") ?: "" } catch (_: Exception) { "" },
                        name = rs.getString("name"),
                        host = rs.getString("host"),
                        port = rs.getInt("port"),
                        username = rs.getString("username"),
                        authType = SshConfig.AuthType.valueOf(rs.getString("auth_type") ?: "PASSWORD"),
                        password = rs.getString("password") ?: "",
                        privateKey = rs.getString("private_key") ?: "",
                        passphrase = rs.getString("passphrase") ?: "",
                        remoteDir = rs.getString("remote_dir") ?: "/tmp",
                        useLocalKey = try { rs.getInt("use_local_key") == 1 } catch (_: Exception) { false },
                        jumpHosts = JumpHostCodec.decode(try { rs.getString("jump_hosts_json") } catch (_: Exception) { "[]" }),
                        sortOrder = try { rs.getInt("sort_order") } catch (_: Exception) { 0 }
                    ))
                }
            }
        }
        return list
    }

    @Synchronized
    fun addConfig(config: SshConfig) {
        connection.prepareStatement("""
            INSERT INTO ssh_config (id, group_id, name, host, port, username, auth_type,
                password, private_key, passphrase, remote_dir, use_local_key, jump_hosts_json, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, config.id)
            stmt.setString(2, config.groupId)
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
            stmt.setString(13, JumpHostCodec.encode(config.jumpHosts))
            stmt.setInt(14, config.sortOrder)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun updateConfig(config: SshConfig) {
        connection.prepareStatement("""
            UPDATE ssh_config SET group_id=?, name=?, host=?, port=?, username=?, auth_type=?,
                password=?, private_key=?, passphrase=?, remote_dir=?, use_local_key=?, jump_hosts_json=?, sort_order=?
            WHERE id=?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, config.groupId)
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
            stmt.setString(12, JumpHostCodec.encode(config.jumpHosts))
            stmt.setInt(13, config.sortOrder)
            stmt.setString(14, config.id)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun removeConfig(id: String) {
        connection.prepareStatement("DELETE FROM script_config WHERE ssh_config_id=?").use { s -> s.setString(1, id); s.executeUpdate() }
        connection.prepareStatement("DELETE FROM ssh_config WHERE id=?").use { s -> s.setString(1, id); s.executeUpdate() }
    }

    @Synchronized
    fun getConfigById(id: String): SshConfig? {
        connection.prepareStatement("SELECT * FROM ssh_config WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return SshConfig(
                    id = rs.getString("id"),
                    groupId = try { rs.getString("group_id") ?: "" } catch (_: Exception) { "" },
                    name = rs.getString("name"),
                    host = rs.getString("host"),
                    port = rs.getInt("port"),
                    username = rs.getString("username"),
                    authType = SshConfig.AuthType.valueOf(rs.getString("auth_type") ?: "PASSWORD"),
                    password = rs.getString("password") ?: "",
                    privateKey = rs.getString("private_key") ?: "",
                    passphrase = rs.getString("passphrase") ?: "",
                    remoteDir = rs.getString("remote_dir") ?: "/tmp",
                    useLocalKey = try { rs.getInt("use_local_key") == 1 } catch (_: Exception) { false },
                    jumpHosts = JumpHostCodec.decode(try { rs.getString("jump_hosts_json") } catch (_: Exception) { "[]" }),
                        sortOrder = try { rs.getInt("sort_order") } catch (_: Exception) { 0 }
                )
            }
        }
        return null
    }

    /** 按分组返回：key = groupId（空串归"默认"），value = 该组配置列表 */
    fun getConfigsByGroup(): Map<String, List<SshConfig>> {
        return getConfigs().groupBy { it.groupId }
    }

    /** 按分组返回，key = SshGroup（null 表示未分组） */
    fun getConfigsByGroupEntity(): Map<SshGroup?, List<SshConfig>> {
        val groups = getGroups().associateBy { it.id }
        return getConfigs().groupBy { groups[it.groupId] }
    }

    // ========== 脚本管理 ==========

    @Synchronized
    fun getScriptsByConfigId(sshConfigId: String): List<ScriptConfig> {
        val list = mutableListOf<ScriptConfig>()
        connection.prepareStatement("SELECT * FROM script_config WHERE ssh_config_id=? ORDER BY script_type, name")
            .use { stmt ->
                stmt.setString(1, sshConfigId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(ScriptConfig(
                            id = rs.getString("id"),
                            sshConfigId = rs.getString("ssh_config_id"),
                            name = rs.getString("name"),
                            scriptType = runCatching { ScriptConfig.ScriptType.valueOf(rs.getString("script_type") ?: "PRE") }.getOrDefault(ScriptConfig.ScriptType.PRE),
                            shellType = runCatching { ScriptConfig.ShellType.valueOf(rs.getString("shell_type") ?: "DEFAULT") }.getOrDefault(ScriptConfig.ShellType.DEFAULT),
                            content = rs.getString("content") ?: "",
                            enabled = rs.getInt("enabled") == 1
                        ))
                    }
                }
            }
        return list
    }

    fun getPreScripts(sshConfigId: String): List<ScriptConfig> =
        getScriptsByConfigId(sshConfigId).filter { it.scriptType == ScriptConfig.ScriptType.PRE }

    fun getPostScripts(sshConfigId: String): List<ScriptConfig> =
        getScriptsByConfigId(sshConfigId).filter { it.scriptType == ScriptConfig.ScriptType.POST }

    fun getLocalPreScripts(sshConfigId: String): List<ScriptConfig> =
        getScriptsByConfigId(sshConfigId).filter { it.scriptType == ScriptConfig.ScriptType.LOCAL_PRE }

    fun getLocalPostScripts(sshConfigId: String): List<ScriptConfig> =
        getScriptsByConfigId(sshConfigId).filter { it.scriptType == ScriptConfig.ScriptType.LOCAL_POST }

    @Synchronized
    fun addScript(script: ScriptConfig) {
        connection.prepareStatement("""
            INSERT INTO script_config (id, ssh_config_id, name, script_type, shell_type, content, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, script.id)
            stmt.setString(2, script.sshConfigId)
            stmt.setString(3, script.name)
            stmt.setString(4, script.scriptType.name)
            stmt.setString(5, script.shellType.name)
            stmt.setString(6, script.content)
            stmt.setInt(7, if (script.enabled) 1 else 0)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun updateScript(script: ScriptConfig) {
        connection.prepareStatement("""
            UPDATE script_config SET name=?, script_type=?, shell_type=?, content=?, enabled=?
            WHERE id=?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, script.name)
            stmt.setString(2, script.scriptType.name)
            stmt.setString(3, script.shellType.name)
            stmt.setString(4, script.content)
            stmt.setInt(5, if (script.enabled) 1 else 0)
            stmt.setString(6, script.id)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun removeScript(id: String) {
        connection.prepareStatement("DELETE FROM script_config WHERE id=?").use { s -> s.setString(1, id); s.executeUpdate() }
    }

    // ========== 上传模板管理 ==========

    @Synchronized
    fun getUploadTemplates(): List<UploadTemplate> {
        val list = mutableListOf<UploadTemplate>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM upload_template ORDER BY group_name, name").use { rs ->
                while (rs.next()) {
                    list.add(UploadTemplate(
                        id = rs.getString("id"),
                        group = rs.getString("group_name") ?: "",
                        name = rs.getString("name"),
                        localPath = rs.getString("local_path") ?: "",
                        sshConfigId = rs.getString("ssh_config_id") ?: "",
                        remotePath = rs.getString("remote_path") ?: "/tmp",
                        remoteFileName = rs.getString("remote_file_name") ?: "",
                        preScript = rs.getString("pre_script") ?: "",
                        postScript = rs.getString("post_script") ?: "",
                        preScriptIds = try { rs.getString("pre_script_ids")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList() } catch (_: Exception) { emptyList() },
                        postScriptIds = try { rs.getString("post_script_ids")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList() } catch (_: Exception) { emptyList() },
                        createTime = rs.getLong("create_time")
                    ))
                }
            }
        }
        return list
    }

    fun getUploadTemplatesByGroup(): Map<String, List<UploadTemplate>> =
        getUploadTemplates().groupBy { it.group.ifEmpty { "默认" } }

    @Synchronized
    fun getUploadTemplateById(id: String): UploadTemplate? {
        connection.prepareStatement("SELECT * FROM upload_template WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return UploadTemplate(
                    id = rs.getString("id"),
                    group = rs.getString("group_name") ?: "",
                    name = rs.getString("name"),
                    localPath = rs.getString("local_path") ?: "",
                    sshConfigId = rs.getString("ssh_config_id") ?: "",
                    remotePath = rs.getString("remote_path") ?: "/tmp",
                    remoteFileName = rs.getString("remote_file_name") ?: "",
                    preScript = rs.getString("pre_script") ?: "",
                    postScript = rs.getString("post_script") ?: "",
                    preScriptIds = try { rs.getString("pre_script_ids")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList() } catch (_: Exception) { emptyList() },
                    postScriptIds = try { rs.getString("post_script_ids")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList() } catch (_: Exception) { emptyList() },
                    createTime = rs.getLong("create_time")
                )
            }
        }
        return null
    }

    @Synchronized
    fun addUploadTemplate(template: UploadTemplate) {
        connection.prepareStatement("""
            INSERT INTO upload_template (id, group_name, name, local_path, ssh_config_id,
                remote_path, remote_file_name, pre_script, post_script, pre_script_ids, post_script_ids, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, template.id); stmt.setString(2, template.group); stmt.setString(3, template.name)
            stmt.setString(4, template.localPath); stmt.setString(5, template.sshConfigId)
            stmt.setString(6, template.remotePath); stmt.setString(7, template.remoteFileName)
            stmt.setString(8, template.preScript); stmt.setString(9, template.postScript)
            stmt.setString(10, template.preScriptIds.joinToString(",")); stmt.setString(11, template.postScriptIds.joinToString(","))
            stmt.setLong(12, template.createTime); stmt.executeUpdate()
        }
    }

    @Synchronized
    fun updateUploadTemplate(template: UploadTemplate) {
        connection.prepareStatement("""
            UPDATE upload_template SET group_name=?, name=?, local_path=?, ssh_config_id=?,
                remote_path=?, remote_file_name=?, pre_script=?, post_script=?, pre_script_ids=?, post_script_ids=?
            WHERE id=?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, template.group); stmt.setString(2, template.name); stmt.setString(3, template.localPath)
            stmt.setString(4, template.sshConfigId); stmt.setString(5, template.remotePath)
            stmt.setString(6, template.remoteFileName); stmt.setString(7, template.preScript)
            stmt.setString(8, template.postScript); stmt.setString(9, template.preScriptIds.joinToString(","))
            stmt.setString(10, template.postScriptIds.joinToString(",")); stmt.setString(11, template.id)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun removeUploadTemplate(id: String) {
        connection.prepareStatement("DELETE FROM upload_template WHERE id=?").use { s -> s.setString(1, id); s.executeUpdate() }
    }

    fun renameUploadTempGroup(group: String, newGroup: String) {
        connection.prepareStatement("UPDATE upload_template SET group_name = ? WHERE group_name = ?").use { stmt ->
            stmt.setString(1, newGroup); stmt.setString(2, group); stmt.executeUpdate()
        }
    }

    // ========== Ansible 分组脚本 ==========

    @Synchronized
    fun getAnsibleScriptsByGroup(groupId: String): List<AnsibleGroupScript> {
        val list = mutableListOf<AnsibleGroupScript>()
        connection.prepareStatement("SELECT * FROM ansible_group_script WHERE group_id=? ORDER BY sort_order, create_time").use { stmt ->
            stmt.setString(1, groupId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) list.add(AnsibleGroupScript(
                    id = rs.getString("id"), groupId = rs.getString("group_id"),
                    name = rs.getString("name"), content = rs.getString("content") ?: "",
                    createTime = rs.getLong("create_time"),
                    sortOrder = try { rs.getInt("sort_order") } catch (_: Exception) { 0 }
                ))
            }
        }
        return list
    }

    @Synchronized
    fun addAnsibleScript(script: AnsibleGroupScript) {
        connection.prepareStatement("""
            INSERT INTO ansible_group_script (id, group_id, name, content, create_time, sort_order)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, script.id); stmt.setString(2, script.groupId)
            stmt.setString(3, script.name); stmt.setString(4, script.content)
            stmt.setLong(5, script.createTime); stmt.setInt(6, script.sortOrder)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun updateAnsibleScript(script: AnsibleGroupScript) {
        connection.prepareStatement("UPDATE ansible_group_script SET name=?, content=?, sort_order=? WHERE id=?").use { stmt ->
            stmt.setString(1, script.name); stmt.setString(2, script.content)
            stmt.setInt(3, script.sortOrder); stmt.setString(4, script.id)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun removeAnsibleScript(id: String) {
        connection.prepareStatement("DELETE FROM ansible_group_script WHERE id=?").use { s -> s.setString(1, id); s.executeUpdate() }
    }

    fun close() {
        try { connection.close() } catch (_: Exception) {}
    }
}
