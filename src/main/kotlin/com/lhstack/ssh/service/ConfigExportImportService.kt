package com.lhstack.ssh.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
import com.lhstack.ssh.model.UploadTemplate
import java.io.File

/**
 * SSH配置导出导入服务
 */
object ConfigExportImportService {
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    /**
     * 导出配置数据类
     */
    data class ExportData(
        val version: Int = 1,
        val exportTime: Long = System.currentTimeMillis(),
        val configs: List<ConfigWithScripts>,
        val uploadTemplates: List<UploadTemplate> = emptyList()
    )
    
    /**
     * 配置及其脚本
     */
    data class ConfigWithScripts(
        val config: SshConfig,
        val preScripts: List<ScriptConfig>,
        val postScripts: List<ScriptConfig>
    )
    
    /**
     * 导出所有配置到JSON文件
     */
    fun exportToFile(file: File): Result<Int> {
        return try {
            val configs = SshConfigService.getConfigs()
            val configsWithScripts = configs.map { config ->
                ConfigWithScripts(
                    config = config,
                    preScripts = SshConfigService.getPreScripts(config.id),
                    postScripts = SshConfigService.getPostScripts(config.id)
                )
            }
            val uploadTemplates = SshConfigService.getUploadTemplates()
            
            val exportData = ExportData(
                configs = configsWithScripts,
                uploadTemplates = uploadTemplates
            )
            file.writeText(gson.toJson(exportData))
            Result.success(configs.size + uploadTemplates.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 导出选中的配置到JSON文件
     */
    fun exportSelectedToFile(file: File, configIds: List<String>, templateIds: List<String> = emptyList()): Result<Int> {
        return try {
            val configs = SshConfigService.getConfigs().filter { it.id in configIds }
            val configsWithScripts = configs.map { config ->
                ConfigWithScripts(
                    config = config,
                    preScripts = SshConfigService.getPreScripts(config.id),
                    postScripts = SshConfigService.getPostScripts(config.id)
                )
            }
            val uploadTemplates = SshConfigService.getUploadTemplates().filter { it.id in templateIds }
            
            val exportData = ExportData(
                configs = configsWithScripts,
                uploadTemplates = uploadTemplates
            )
            file.writeText(gson.toJson(exportData))
            Result.success(configs.size + uploadTemplates.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 从JSON文件导入配置
     * @param file JSON文件
     * @param overwrite 是否覆盖同名配置
     * @return 导入的配置数量
     */
    fun importFromFile(file: File, overwrite: Boolean = false): Result<ImportResult> {
        return try {
            val json = file.readText()
            val exportData = gson.fromJson(json, ExportData::class.java)
            
            var imported = 0
            var skipped = 0
            var updated = 0
            
            // 导入SSH配置
            exportData.configs.forEach { configWithScripts ->
                val config = configWithScripts.config
                val existingConfig = SshConfigService.getConfigs()
                    .find { it.group == config.group && it.name == config.name }
                
                if (existingConfig != null) {
                    if (overwrite) {
                        // 更新现有配置
                        val updatedConfig = config.copy(id = existingConfig.id)
                        SshConfigService.updateConfig(updatedConfig)
                        
                        // 删除旧脚本
                        SshConfigService.getScriptsByConfigId(existingConfig.id).forEach {
                            SshConfigService.removeScript(it.id)
                        }
                        
                        // 添加新脚本
                        configWithScripts.preScripts.forEach { script ->
                            SshConfigService.addScript(script.copy(
                                id = System.currentTimeMillis().toString() + "_" + script.id,
                                sshConfigId = existingConfig.id
                            ))
                        }
                        configWithScripts.postScripts.forEach { script ->
                            SshConfigService.addScript(script.copy(
                                id = System.currentTimeMillis().toString() + "_" + script.id,
                                sshConfigId = existingConfig.id
                            ))
                        }
                        updated++
                    } else {
                        skipped++
                    }
                } else {
                    // 添加新配置
                    val newId = System.currentTimeMillis().toString() + "_" + config.id
                    val newConfig = config.copy(id = newId)
                    SshConfigService.addConfig(newConfig)
                    
                    // 添加脚本
                    configWithScripts.preScripts.forEach { script ->
                        SshConfigService.addScript(script.copy(
                            id = System.currentTimeMillis().toString() + "_" + script.id,
                            sshConfigId = newId
                        ))
                    }
                    configWithScripts.postScripts.forEach { script ->
                        SshConfigService.addScript(script.copy(
                            id = System.currentTimeMillis().toString() + "_" + script.id,
                            sshConfigId = newId
                        ))
                    }
                    imported++
                }
            }
            
            // 导入上传模板
            var templateImported = 0
            var templateSkipped = 0
            var templateUpdated = 0
            
            exportData.uploadTemplates.forEach { template ->
                val existingTemplate = SshConfigService.getUploadTemplates()
                    .find { it.group == template.group && it.name == template.name }
                
                if (existingTemplate != null) {
                    if (overwrite) {
                        val updatedTemplate = template.copy(id = existingTemplate.id)
                        SshConfigService.updateUploadTemplate(updatedTemplate)
                        templateUpdated++
                    } else {
                        templateSkipped++
                    }
                } else {
                    val newTemplate = template.copy(
                        id = System.currentTimeMillis().toString() + "_" + template.id
                    )
                    SshConfigService.addUploadTemplate(newTemplate)
                    templateImported++
                }
            }
            
            Result.success(ImportResult(
                imported + templateImported,
                updated + templateUpdated,
                skipped + templateSkipped
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 导入结果
     */
    data class ImportResult(
        val imported: Int,
        val updated: Int,
        val skipped: Int
    ) {
        val total: Int get() = imported + updated + skipped
    }
}
