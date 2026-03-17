package com.lhstack.ssh

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object PluginIcons {
    @JvmField
    val BatchUpload: Icon = IconLoader.getIcon("/batchUploadIcon.svg", PluginIcons::class.java)

    @JvmField
    val BatchDelete: Icon = IconLoader.getIcon("/batchDeleteIcon.svg", PluginIcons::class.java)
}
