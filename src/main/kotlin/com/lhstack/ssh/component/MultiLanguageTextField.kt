package com.lhstack.ssh.component

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.highlighter.HighlighterFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.LanguageTextField

class MultiLanguageTextField(
    private val languageFileType: LanguageFileType,
    project: Project,
    value: String,
    private val isLineNumbersShown: Boolean = true
) : LanguageTextField(languageFileType.language, project, value, false), Disposable {

    init {
        border = null
    }

    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
    }

    override fun createEditor(): EditorEx {
        val editorEx = EditorFactory.getInstance()
            .createEditor(document, project, languageFileType, false) as EditorEx
        editorEx.highlighter = HighlighterFactory.createHighlighter(project, languageFileType)
        
        PsiDocumentManager.getInstance(project).getPsiFile(editorEx.document)?.let { psiFile ->
            DaemonCodeAnalyzer.getInstance(project).setHighlightingEnabled(psiFile, true)
        }
        
        editorEx.setBorder(null)
        editorEx.settings.apply {
            additionalLinesCount = 0
            additionalColumnsCount = 1
            isLineNumbersShown = this@MultiLanguageTextField.isLineNumbersShown
            isUseSoftWraps = true
            lineCursorWidth = 1
            isLineMarkerAreaShown = false
            setRightMargin(-1)
        }
        return editorEx
    }
}
