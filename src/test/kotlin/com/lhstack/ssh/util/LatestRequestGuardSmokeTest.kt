package com.lhstack.ssh.util

private fun assertGuard(condition: Boolean, message: String) {
    if (!condition) {
        throw IllegalStateException(message)
    }
}

fun main() {
    val guard = LatestRequestGuard()
    val first = guard.nextToken()
    val second = guard.nextToken()

    assertGuard(!guard.isLatest(first), "older token should become stale after a newer request starts")
    assertGuard(guard.isLatest(second), "newest token should be accepted")
}
