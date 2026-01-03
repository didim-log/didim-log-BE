package com.didimlog.ui.dto

/**
 * AI 사용량 조회 응답 DTO
 */
data class AiUsageResponse(
    val limit: Int,
    val usage: Int,
    val remaining: Int,
    val isServiceEnabled: Boolean
)



