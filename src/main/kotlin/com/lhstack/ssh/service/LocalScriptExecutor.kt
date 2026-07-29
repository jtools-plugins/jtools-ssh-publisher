package com.lhstack.ssh.service

import com.lhstack.ssh.model.ScriptConfig
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 本地脚本执行器（LOCAL_PRE / LOCAL_POST 使用 ProcessBuilder 在本机执行）
 */
object LocalScriptExecutor {

    private const val TIMEOUT_SECONDS = 300L

    data class ExecutionResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean
    ) {
        val succeeded: Boolean
            get() = !timedOut && exitCode == 0

        val failureDescription: String
            get() = if (timedOut) {
                "执行超过 ${TIMEOUT_SECONDS}s，进程已强制终止"
            } else {
                "退出码为 $exitCode"
            }
    }

    /**
     * 执行本地脚本并返回 stdout、stderr、退出码和超时状态。
     * 脚本内容写入与 Shell 类型匹配的临时文件，执行完毕后删除。
     *
     * @param workDir 脚本工作目录（当前项目根目录），必须存在且为目录
     */
    fun execute(script: ScriptConfig, workDir: String): ExecutionResult {
        val tmpFile = createTempScript(script)
        var process: Process? = null
        return try {
            process = ProcessBuilder(buildCommand(script, tmpFile.absolutePath))
                .directory(resolveWorkDir(workDir))
                .start()
            collectResult(process)
        } finally {
            if (process?.isAlive == true) {
                process.destroyForcibly()
            }
            tmpFile.delete()
        }
    }

    private fun collectResult(process: Process): ExecutionResult {
        val stdoutCollector = StreamCollector(process.inputStream, "stdout")
        val stderrCollector = StreamCollector(process.errorStream, "stderr")
        stdoutCollector.start()
        stderrCollector.start()

        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }

        val stdout = stdoutCollector.await()
        val stderr = stderrCollector.await()
        return ExecutionResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = process.exitValue(),
            timedOut = !finished
        )
    }

    /** 工作目录必须是已存在的目录，否则直接报错而不是回退到 IDE 进程目录。 */
    private fun resolveWorkDir(workDir: String): java.io.File {
        val dir = java.io.File(workDir)
        require(dir.isDirectory) { "本地脚本工作目录不可用: $workDir" }
        return dir
    }

    private fun buildCommand(script: ScriptConfig, scriptPath: String): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when (script.shellType) {
            ScriptConfig.ShellType.BASH -> listOf("bash", scriptPath)
            ScriptConfig.ShellType.ZSH -> listOf("zsh", scriptPath)
            ScriptConfig.ShellType.SH -> listOf("sh", scriptPath)
            ScriptConfig.ShellType.CMD -> listOf("cmd", "/d", "/c", scriptPath)
            ScriptConfig.ShellType.POWERSHELL -> listOf(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                scriptPath
            )

            ScriptConfig.ShellType.DEFAULT -> if (os.contains("win")) {
                listOf("cmd", "/d", "/c", scriptPath)
            } else {
                listOf("sh", scriptPath)
            }
        }
    }

    private fun createTempScript(script: ScriptConfig): java.io.File {
        val tmp = java.io.File.createTempFile("jtools_local_script_", scriptFileSuffix(script))
        tmp.writeText(script.content)
        tmp.setExecutable(true)
        return tmp
    }

    private fun scriptFileSuffix(script: ScriptConfig): String {
        return when (script.shellType) {
            ScriptConfig.ShellType.CMD -> ".cmd"
            ScriptConfig.ShellType.POWERSHELL -> ".ps1"
            ScriptConfig.ShellType.DEFAULT -> {
                if (System.getProperty("os.name").lowercase().contains("win")) ".cmd" else ".sh"
            }

            else -> ".sh"
        }
    }

    private class StreamCollector(
        private val input: InputStream,
        private val streamName: String
    ) {
        @Volatile
        private var output = ""

        @Volatile
        private var failure: Throwable? = null

        private val thread = Thread({
            try {
                output = input.bufferedReader().use { it.readText() }
            } catch (error: Throwable) {
                failure = error
            }
        }, "jtools-local-script-$streamName-reader").apply {
            isDaemon = true
        }

        fun start() {
            thread.start()
        }

        fun await(): String {
            thread.join()
            failure?.let { throw IllegalStateException("读取本地脚本 $streamName 失败", it) }
            return output
        }
    }
}
