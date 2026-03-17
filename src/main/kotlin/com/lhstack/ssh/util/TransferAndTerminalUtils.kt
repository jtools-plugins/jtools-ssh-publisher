package com.lhstack.ssh.util

data class NormalizedTerminalSize(
    val columns: Int,
    val rows: Int,
    val width: Int,
    val height: Int
)

object UploadPathUtils {

    fun buildRemotePath(directory: String, fileName: String): String {
        val normalizedDirectory = when {
            directory.isBlank() -> "/"
            directory == "/" -> "/"
            directory.endsWith("/") -> directory.dropLast(1)
            else -> directory
        }
        return if (normalizedDirectory == "/") "/$fileName" else "$normalizedDirectory/$fileName"
    }
}

object TerminalIoUtils {

    fun normalizeTerminalSize(columns: Int, rows: Int, width: Int = 0, height: Int = 0): NormalizedTerminalSize {
        return NormalizedTerminalSize(
            columns = columns.coerceAtLeast(1),
            rows = rows.coerceAtLeast(1),
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0)
        )
    }

    fun chunkBytes(bytes: ByteArray, maxChunkBytes: Int = 4096): List<ByteArray> {
        require(maxChunkBytes > 0) { "maxChunkBytes must be positive" }
        if (bytes.isEmpty()) {
            return emptyList()
        }
        return bytes.asList()
            .chunked(maxChunkBytes)
            .map { chunk -> ByteArray(chunk.size) { index -> chunk[index] } }
    }

    fun chunkUtf8(text: String, maxChunkBytes: Int = 4096): List<ByteArray> {
        return chunkBytes(text.toByteArray(Charsets.UTF_8), maxChunkBytes)
    }
}
