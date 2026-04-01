package com.lhstack.ssh.view

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal data class RemoteCreateRequest(
    val relativePath: String,
    val isDirectory: Boolean
)

internal object RemoteCreateRequestParser {

    fun parseSingle(input: String): RemoteCreateRequest {
        val normalized = normalizeLine(input, 1)
        return RemoteCreateRequest(normalized.path, normalized.isDirectory)
    }

    fun parseBatch(input: String): List<RemoteCreateRequest> {
        val requests = linkedMapOf<String, RemoteCreateRequest>()
        input.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) {
                return@forEachIndexed
            }
            val normalized = normalizeLine(line, index + 1)
            val key = normalized.path
            val request = RemoteCreateRequest(normalized.path, normalized.isDirectory)
            val existing = requests[key]
            when {
                existing == null -> requests[key] = request
                existing.isDirectory != request.isDirectory -> {
                    throw IllegalArgumentException("第 ${index + 1} 行与前面的路径类型冲突: ${normalized.original}")
                }
            }
        }
        if (requests.isEmpty()) {
            throw IllegalArgumentException("请至少输入一条路径")
        }
        return requests.values.toList()
    }

    private fun normalizeLine(input: String, lineNumber: Int): NormalizedLine {
        val original = input.trim()
        if (original.isBlank()) {
            throw IllegalArgumentException("第 $lineNumber 行不能为空")
        }
        if (original == "/") {
            throw IllegalArgumentException("第 $lineNumber 行不能只输入 /")
        }
        if (original.startsWith("/")) {
            throw IllegalArgumentException("第 $lineNumber 行必须使用相对路径: $original")
        }

        val normalizedSeparators = original.replace('\\', '/')
        val isDirectory = normalizedSeparators.endsWith("/")
        val segments = normalizedSeparators.split('/')
            .filter { it.isNotBlank() }

        if (segments.isEmpty()) {
            throw IllegalArgumentException("第 $lineNumber 行缺少有效路径")
        }
        if (segments.any { it == "." || it == ".." }) {
            throw IllegalArgumentException("第 $lineNumber 行不能包含 . 或 .. : $original")
        }

        return NormalizedLine(
            original = original,
            path = segments.joinToString("/"),
            isDirectory = isDirectory
        )
    }

    private data class NormalizedLine(
        val original: String,
        val path: String,
        val isDirectory: Boolean
    )
}

internal class RemoteCreateEntryDialog(
    project: Project,
    private val targetDirectory: String
) : DialogWrapper(project, true) {

    private val pathField = JBTextField()

    init {
        title = "新建"
        setOKButtonText("确认")
        init()
    }

    val request: RemoteCreateRequest
        get() = RemoteCreateRequestParser.parseSingle(pathField.text)

    override fun createCenterPanel(): JComponent {
        val tipLabel = JBLabel(
            "<html><font color='gray'>输入 a/b/c 创建文件，输入 a/b/c/ 创建文件夹。自动补齐缺失的父目录。</font></html>"
        )
        val targetLabel = JBLabel("目标目录: $targetDirectory")

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(targetLabel, BorderLayout.NORTH)
            add(pathField, BorderLayout.CENTER)
            add(tipLabel, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = pathField

    override fun doValidate(): ValidationInfo? {
        return try {
            RemoteCreateRequestParser.parseSingle(pathField.text)
            null
        } catch (e: IllegalArgumentException) {
            ValidationInfo(e.message ?: "路径无效", pathField)
        }
    }
}

internal class RemoteBatchCreateDialog(
    project: Project,
    private val targetDirectory: String
) : DialogWrapper(project, true) {

    private val textArea = JBTextArea(12, 50).apply {
        lineWrap = false
        text = ""
    }

    init {
        title = "批量创建"
        setOKButtonText("确认")
        init()
    }

    val requests: List<RemoteCreateRequest>
        get() = RemoteCreateRequestParser.parseBatch(textArea.text)

    override fun createCenterPanel(): JComponent {
        val tipLabel = JBLabel(
            "<html><font color='gray'>每行一条相对路径。输入 a/b/c 创建文件，输入 a/b/c/ 创建文件夹。空行会被忽略。</font></html>"
        )
        val targetLabel = JBLabel("目标目录: $targetDirectory")

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(targetLabel, BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(tipLabel, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doValidate(): ValidationInfo? {
        return try {
            RemoteCreateRequestParser.parseBatch(textArea.text)
            null
        } catch (e: IllegalArgumentException) {
            ValidationInfo(e.message ?: "输入无效", textArea)
        }
    }
}
