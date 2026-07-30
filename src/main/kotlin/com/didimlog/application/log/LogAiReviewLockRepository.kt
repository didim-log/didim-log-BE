package com.didimlog.application.log

import java.time.LocalDateTime

interface LogAiReviewLockRepository {
    fun tryAcquireLock(
        logId: String,
        now: LocalDateTime,
        expiresAt: LocalDateTime
    ): AiReviewLock?

    fun renewLock(
        logId: String,
        lock: AiReviewLock,
        now: LocalDateTime,
        expiresAt: LocalDateTime
    ): Boolean

    fun markCompleted(
        logId: String,
        lock: AiReviewLock,
        now: LocalDateTime,
        review: String,
        durationMillis: Long
    ): Boolean

    fun markFailed(
        logId: String,
        lock: AiReviewLock,
        now: LocalDateTime
    ): Boolean

    fun isInProgress(logId: String, now: LocalDateTime): Boolean
}

data class AiReviewLock(val version: Long)
