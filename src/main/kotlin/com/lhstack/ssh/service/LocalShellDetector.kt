package com.lhstack.ssh.service

import com.lhstack.ssh.model.ScriptConfig
import java.io.File

/**
 * 检测当前操作系统下可用的本地 Shell。
 *
 * - Windows：固定返回 CMD；若能在 PATH 中找到 powershell.exe / pwsh.exe 则追加 POWERSHELL
 * - macOS / Linux：读取 /etc/shells 过滤可执行文件，映射到 ShellType；
 *   若读取失败则回退到硬编码列表（macOS: zsh/bash/sh，Linux: bash/sh）
 */
object LocalShellDetector {

    /**
     * 返回当前 OS 可用的 [ScriptConfig.ShellType] 列表，顺序即推荐优先级。
     * 列表永远不为空（至少包含 DEFAULT）。
     */
    fun availableShells(): List<ScriptConfig.ShellType> {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> detectWindows()
            os.contains("mac") -> detectUnix(macFallback)
            else               -> detectUnix(linuxFallback)
        }
    }

    /** 返回当前 OS 的默认 Shell（availableShells 第一项）。 */
    fun defaultShell(): ScriptConfig.ShellType = availableShells().first()

    // ---- Windows -------------------------------------------------------

    private fun detectWindows(): List<ScriptConfig.ShellType> {
        val list = mutableListOf(ScriptConfig.ShellType.CMD)
        if (findInPath("powershell.exe") || findInPath("pwsh.exe")) {
            list.add(ScriptConfig.ShellType.POWERSHELL)
        }
        return list
    }

    // ---- Unix (macOS / Linux) -------------------------------------------

    private val macFallback = listOf(
        ScriptConfig.ShellType.ZSH,
        ScriptConfig.ShellType.BASH,
        ScriptConfig.ShellType.SH
    )

    private val linuxFallback = listOf(
        ScriptConfig.ShellType.BASH,
        ScriptConfig.ShellType.SH
    )

    /** /etc/shells 里的路径名 -> ShellType（只匹配末尾 basename）。 */
    private val shellNameMap = mapOf(
        "zsh"  to ScriptConfig.ShellType.ZSH,
        "bash" to ScriptConfig.ShellType.BASH,
        "sh"   to ScriptConfig.ShellType.SH,
        "dash" to ScriptConfig.ShellType.SH    // dash 视同 sh
    )

    private fun detectUnix(fallback: List<ScriptConfig.ShellType>): List<ScriptConfig.ShellType> {
        val etcShells = File("/etc/shells")
        if (!etcShells.canRead()) return fallback.filter { isExecutable(it) }.ifEmpty { fallback }

        val found = mutableListOf<ScriptConfig.ShellType>()
        val seen  = mutableSetOf<ScriptConfig.ShellType>()

        etcShells.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
            val name = File(trimmed).name.lowercase()
            val type = shellNameMap[name] ?: return@forEachLine
            if (seen.add(type) && File(trimmed).canExecute()) {
                found.add(type)
            }
        }

        // 按 fallback 顺序排列，保证优先级稳定
        val ordered = fallback.filter { it in seen && it in found }.toMutableList()
        // 追加 /etc/shells 里有但 fallback 没列出的
        found.filter { it !in ordered }.forEach { ordered.add(it) }

        return ordered.ifEmpty { fallback }
    }

    // ---- helpers -------------------------------------------------------

    private fun isExecutable(type: ScriptConfig.ShellType): Boolean {
        val name = when (type) {
            ScriptConfig.ShellType.ZSH  -> "zsh"
            ScriptConfig.ShellType.BASH -> "bash"
            ScriptConfig.ShellType.SH   -> "sh"
            else -> return false
        }
        return findInPath(name)
    }

    private fun findInPath(executable: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            File(dir, executable).let { it.exists() && it.canExecute() }
        }
    }
}
