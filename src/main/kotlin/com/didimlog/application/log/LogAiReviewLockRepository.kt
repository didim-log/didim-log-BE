package com.didimlog.application.log

import com.didimlog.domain.enums.AiReviewStatus
import java.time.LocalDateTime

interface LogAiReviewLockRepository {
    fun tryAcquireLock(logId: String, now: LocalDateTime, expiresAt: LocalDateTime): Boolean
    fun markCompleted(logId: String, review: String): Boolean
    fun markFailed(logId: String): Boolean
    fun isInProgress(logId: String, now: LocalDateTime): Boolean
}


