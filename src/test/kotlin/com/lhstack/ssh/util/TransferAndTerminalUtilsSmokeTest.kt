package com.lhstack.ssh.util

private fun assertCondition(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) {
        throw IllegalStateException(lazyMessage())
    }
}

fun main() {
    assertCondition(
        UploadPathUtils.buildRemotePath("/tmp", "demo.txt") == "/tmp/demo.txt"
    ) { "should join a plain directory and file name" }

    assertCondition(
        UploadPathUtils.buildRemotePath("/tmp/", "demo.txt") == "/tmp/demo.txt"
    ) { "should avoid duplicate slash when directory already ends with /" }

    val normalized = TerminalIoUtils.normalizeTerminalSize(columns = 0, rows = -4, width = -1, height = 18)
    assertCondition(normalized.columns == 1 && normalized.rows == 1) {
        "terminal rows and columns should be clamped to at least 1"
    }
    assertCondition(normalized.width == 0 && normalized.height == 18) {
        "terminal pixel size should never be negative"
    }

    val chunks = TerminalIoUtils.chunkUtf8("你".repeat(5000), maxChunkBytes = 4096)
    assertCondition(chunks.size > 1) { "large UTF-8 payload should be split into multiple chunks" }

    val reconstructed = buildList<Byte> {
        chunks.forEach { chunk: ByteArray ->
            chunk.forEach { byte -> add(byte) }
        }
    }.toByteArray().toString(Charsets.UTF_8)
    assertCondition(reconstructed == "你".repeat(5000)) {
        "chunked payload should reconstruct back to the original text"
    }
}
