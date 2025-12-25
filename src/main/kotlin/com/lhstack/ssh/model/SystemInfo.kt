package com.lhstack.ssh.model

/**
 * 系统信息数据模型
 */
data class SystemInfo(
    val cpuUsage: Double = 0.0,           // CPU使用率 (0-100)
    val memoryUsed: Long = 0,             // 已用内存 (bytes)
    val memoryTotal: Long = 0,            // 总内存 (bytes)
    val diskUsed: Long = 0,               // 已用磁盘 (bytes)
    val diskTotal: Long = 0,              // 总磁盘 (bytes)
    val processCount: Int = 0,            // 进程数
    val timestamp: Long = System.currentTimeMillis()
) {
    val memoryUsagePercent: Double 
        get() = if (memoryTotal > 0) memoryUsed * 100.0 / memoryTotal else 0.0
    
    val diskUsagePercent: Double 
        get() = if (diskTotal > 0) diskUsed * 100.0 / diskTotal else 0.0
    
    companion object {
        /**
         * 格式化字节数为人类可读格式
         */
        fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "${bytes}B"
                bytes < 1024 * 1024 -> String.format("%.1fK", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1fM", bytes / 1024.0 / 1024.0)
                else -> String.format("%.1fG", bytes / 1024.0 / 1024.0 / 1024.0)
            }
        }
    }
}
