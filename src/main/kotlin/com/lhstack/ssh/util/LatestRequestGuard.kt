package com.lhstack.ssh.util

import java.util.concurrent.atomic.AtomicLong

class LatestRequestGuard {

    private val current = AtomicLong(0)

    fun nextToken(): Long = current.incrementAndGet()

    fun isLatest(token: Long): Boolean = current.get() == token
}
