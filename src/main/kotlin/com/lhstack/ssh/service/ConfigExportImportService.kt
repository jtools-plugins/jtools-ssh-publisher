package com.lhstack.ssh.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.lhstack.ssh.model.ScriptConfig
import com.lhstack.ssh.model.SshConfig
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
        val configs: List<ConfigWithScripts>
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
            
            val exportData = ExportData(configs = configsWithScripts)
            file.writeText(gson.toJson(exportData))
            Result.success(configs.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 导出选中的配置到JSON文件
     */
    fun exportSelectedToFile(file: File, configIds: List<String>): Result<Int> {
        return try {
            val configs = SshConfigService.getConfigs().filter { it.id in configIds }
            val configsWithScripts = configs.map { config ->
                ConfigWithScripts(
                    config = config,
                    preScripts = SshConfigService.getPreScripts(config.id),
                    postScripts = SshConfigService.getPostScripts(config.id)
                )
            }
            
            val exportData = ExportData(configs = configsWithScripts)
            file.writeText(gson.toJson(exportData))
            Result.success(configs.size)
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
            
            Result.success(ImportResult(imported, updated, skipped))
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
