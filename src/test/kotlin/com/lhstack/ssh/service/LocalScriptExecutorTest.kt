package com.lhstack.ssh.service

import com.lhstack.ssh.model.ScriptConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalScriptExecutorTest {

    @Test
    fun captures_stdout_stderr_and_exit_code_for_every_available_shell() {
        val workDir = createTempDirectory("local-script-executor-test").toFile()
        try {
            LocalShellDetector.availableShells().forEach { shellType ->
                val result = LocalScriptExecutor.execute(
                    ScriptConfig(
                        scriptType = ScriptConfig.ScriptType.LOCAL_PRE,
                        shellType = shellType,
                        content = failingScript(shellType)
                    ),
                    workDir.absolutePath
                )

                assertTrue(result.stdout.contains("local-stdout"), "$shellType 未捕获 stdout")
                assertTrue(result.stderr.contains("local-stderr"), "$shellType 未捕获 stderr")
                assertEquals(7, result.exitCode, "$shellType 未返回真实退出码")
                assertFalse(result.succeeded, "$shellType 非零退出码不应成功")
                assertFalse(result.timedOut, "$shellType 不应超时")
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun failingScript(shellType: ScriptConfig.ShellType): String {
        return when (shellType) {
            ScriptConfig.ShellType.CMD -> """
                @echo off
                echo local-stdout
                echo local-stderr 1>&2
                exit /b 7
            """.trimIndent()

            ScriptConfig.ShellType.POWERSHELL -> """
                [Console]::Out.WriteLine("local-stdout")
                [Console]::Error.WriteLine("local-stderr")
                exit 7
            """.trimIndent()

            ScriptConfig.ShellType.DEFAULT,
            ScriptConfig.ShellType.BASH,
            ScriptConfig.ShellType.ZSH,
            ScriptConfig.ShellType.SH -> """
                printf '%s\n' 'local-stdout'
                printf '%s\n' 'local-stderr' >&2
                exit 7
            """.trimIndent()
        }
    }
}
