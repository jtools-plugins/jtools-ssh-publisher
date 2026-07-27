package com.lhstack.ssh.service

import com.lhstack.ssh.model.ScriptConfig
import java.util.concurrent.TimeUnit

/**
 * 本地脚本执行器（LOCAL_PRE / LOCAL_POST 使用 ProcessBuilder 在本机执行）
 */
object LocalScriptExecutor {

    private const val TIMEOUT_SECONDS = 120L

    /**
     * 执行本地脚本，返回合并后的 stdout + stderr 输出。
     * 脚本内容写入临时文件再执行，执行完毕后删除。
     */
    fun execute(script: ScriptConfig): String {
        val command = buildCommand(script)
        val tmpFile = createTempScript(script.content)
        return try {
            val process = ProcessBuilder(command + tmpFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "$output\n[超时：脚本执行超过 ${TIMEOUT_SECONDS}s 已强制终止]"
            } else {
                output
            }
        } finally {
            tmpFile.delete()
        }
    }

    private fun buildCommand(script: ScriptConfig): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when (script.shellType) {
            ScriptConfig.ShellType.BASH -> listOf("bash")
            ScriptConfig.ShellType.ZSH  -> listOf("zsh")
            ScriptConfig.ShellType.SH   -> listOf("sh")
            ScriptConfig.ShellType.CMD  -> listOf("cmd", "/c")
            ScriptConfig.ShellType.POWERSHELL -> listOf("powershell", "-ExecutionPolicy", "Bypass", "-File")
            ScriptConfig.ShellType.DEFAULT -> if (os.contains("win")) listOf("cmd", "/c") else listOf("sh")
        }
    }

    private fun createTempScript(content: String): java.io.File {
        val suffix = ".sh"
        val tmp = java.io.File.createTempFile("jtools_local_script_", suffix)
        tmp.writeText(content)
        tmp.setExecutable(true)
        return tmp
    }
}
