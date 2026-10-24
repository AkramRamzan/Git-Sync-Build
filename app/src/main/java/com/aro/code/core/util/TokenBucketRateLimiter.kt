package com.aro.code.core.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token-bucket rate limiter for GitHub API.
 * Tracks X-RateLimit-Remaining header values and suspends callers
 * when the bucket is near-empty until the reset window expires.
 */
class TokenBucketRateLimiter(private val minRemaining: Int = 10) {
    private val mutex = Mutex()
    private var remaining: Int = 5_000
    private var resetEpochSeconds: Long = 0

    suspend fun recordHeaders(remaining: Int, resetEpoch: Long) {
        mutex.withLock {
            this.remaining = remaining
            this.resetEpochSeconds = resetEpoch
        }
    }

    suspend fun acquirePermit() {
        val waitMs = mutex.withLock {
            if (remaining <= minRemaining) {
                val now = System.currentTimeMillis() / 1_000
                ((resetEpochSeconds - now) * 1_000).coerceAtLeast(1_000)
            } else {
                0L
            }
        }
        if (waitMs > 0L) {
            kotlinx.coroutines.delay(waitMs)
        }
    }
}
