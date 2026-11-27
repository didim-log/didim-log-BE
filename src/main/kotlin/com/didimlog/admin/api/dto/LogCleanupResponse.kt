package com.didimlog.admin.api.dto

import com.didimlog.admin.application.LogCleanupMode
import java.time.LocalDateTime

/**
 * 로그 정리 응답 DTO
 */
data class LogCleanupResponse(
    val message: String,
    val mode: LogCleanupMode,
    val referenceDays: Int,
    val cutoffAt: LocalDateTime,
    val deletedCount: Long
)












