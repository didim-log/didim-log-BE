package com.didimlog.ui.dto

import jakarta.validation.constraints.NotNull

/**
 * AI 리뷰 요청 정책 업데이트 요청 DTO
 */
data class AiReviewPolicyUpdateRequest(
    @field:NotNull(message = "requireBojForAiReview는 필수입니다.")
    val requireBojForAiReview: Boolean
)
