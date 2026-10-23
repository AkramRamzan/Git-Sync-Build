package com.aro.code.core.util

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Generic exponential backoff utility with jitter.
 * Used for both Gemini API (429/503) and GitHub API rate limits.
 */
suspend fun <T> retryWithExponentialBackoff(
    maxAttempts: Int = 5,
    initialDelayMs: Long = 1_000,
    maxDelayMs: Long = 32_000,
    block: suspend () -> T
): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            val base = (initialDelayMs * 2.0.pow(attempt)).toLong()
            val jitter = Random.nextLong(0, initialDelayMs / 2)
            delay(min(base + jitter, maxDelayMs))
        }
    }
    throw lastError ?: RuntimeException("Retry failed")
}
