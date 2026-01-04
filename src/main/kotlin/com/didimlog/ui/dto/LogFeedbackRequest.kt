package com.didimlog.ui.dto

import com.didimlog.domain.enums.AiFeedbackStatus
import jakarta.validation.constraints.NotNull

/**
 * AI 리뷰 피드백 요청 DTO
 */
data class LogFeedbackRequest(
    @field:NotNull(message = "피드백 상태는 필수입니다.")
    val status: AiFeedbackStatus,
    
    val reason: String? = null
)











