package com.didimlog.ui.dto

/**
 * AI 서비스 상태 응답 DTO
 */
data class AiStatusResponse(
    val isEnabled: Boolean,
    val todayGlobalUsage: Int,
    val globalLimit: Int,
    val userLimit: Int
)













