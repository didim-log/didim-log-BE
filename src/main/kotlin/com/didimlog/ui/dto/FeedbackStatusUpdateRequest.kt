package com.didimlog.ui.dto

import com.didimlog.domain.enums.FeedbackStatus
import jakarta.validation.constraints.NotNull

/**
 * 피드백 상태 변경 요청 DTO
 */
data class FeedbackStatusUpdateRequest(
    @field:NotNull(message = "상태 값은 필수입니다.")
    val status: FeedbackStatus
)

