package com.didimlog.ui.dto

import java.time.LocalDateTime

/**
 * 시스템 상태 응답 DTO
 */
data class SystemStatusResponse(
    val underMaintenance: Boolean,
    val maintenanceMessage: String? = null,
    val startTime: LocalDateTime? = null, // 점검 시작 시간
    val endTime: LocalDateTime? = null,   // 점검 종료 시간
    val noticeId: String? = null           // 관련 공지사항 ID
)



