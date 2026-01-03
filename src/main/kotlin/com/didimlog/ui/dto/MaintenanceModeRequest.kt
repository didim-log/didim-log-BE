package com.didimlog.ui.dto

import java.time.LocalDateTime

/**
 * 유지보수 모드 요청 DTO
 */
data class MaintenanceModeRequest(
    val enabled: Boolean,
    val startTime: LocalDateTime? = null, // 점검 시작 시간
    val endTime: LocalDateTime? = null,   // 점검 종료 시간
    val noticeId: String? = null           // 관련 공지사항 ID
)



