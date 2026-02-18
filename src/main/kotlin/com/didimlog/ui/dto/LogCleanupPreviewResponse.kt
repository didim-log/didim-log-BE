package com.didimlog.ui.dto

import com.didimlog.application.admin.LogCleanupMode
import java.time.LocalDateTime

/**
 * 로그 정리 미리보기 응답 DTO
 */
data class LogCleanupPreviewResponse(
    val mode: LogCleanupMode,
    val referenceDays: Int,
    val cutoffAt: LocalDateTime,
    val deletableCount: Long,
    val statusBreakdown: Map<String, Long>
)
