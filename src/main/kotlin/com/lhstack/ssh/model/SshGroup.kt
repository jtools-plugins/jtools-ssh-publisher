package com.lhstack.ssh.model

import java.io.Serializable
import java.util.UUID

/**
 * SSH 连接分组（独立分组表）
 *
 * ssh_config 通过 group_id 关联此表。
 * 分组重命名只改本表 name，关联连接自动跟随。
 * 分组删除时，关联连接 group_id 置空（归入默认），Ansible 脚本级联删除。
 */
data class SshGroup(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var name: String = ""
) : Serializable

/**
 * Ansible 分组脚本（按分组持久化，分组删除时级联删除）
 */
data class AnsibleGroupScript(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var groupId: String = "",
    var name: String = "",
    var content: String = "",
    var createTime: Long = System.currentTimeMillis(),
    /** 分组内排序，越小越靠前 */
    var sortOrder: Int = 0
) : Serializable
