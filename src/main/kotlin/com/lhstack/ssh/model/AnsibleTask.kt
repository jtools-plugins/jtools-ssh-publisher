package com.lhstack.ssh.model

/**
 * 单台服务器的 Ansible 执行任务（内存态，不持久化）
 */
data class AnsibleTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val config: SshConfig,
    var script: String = "",
    var status: Status = Status.PENDING,
    val logs: MutableList<String> = mutableListOf()
) {
    enum class Status { PENDING, RUNNING, SUCCESS, FAILED, STOPPED }

    fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
        logs.add("[$ts] $msg")
    }
}
