package com.didimlog.application.log

import java.time.LocalDateTime

interface LogAiReviewLockRepository {
    fun tryAcquireLock(logId: String, now: LocalDateTime, expiresAt: LocalDateTime): Boolean
    fun markCompleted(logId: String, review: String, durationMillis: Long): Boolean
    fun markFailed(logId: String): Boolean
    fun isInProgress(logId: String, now: LocalDateTime): Boolean
}


