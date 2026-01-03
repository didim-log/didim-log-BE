package com.didimlog.ui.dto

import com.didimlog.domain.enums.FeedbackType
import jakarta.validation.constraints.Size

/**
 * 피드백 생성 요청 DTO
 */
data class FeedbackCreateRequest(
    @field:Size(min = 10, message = "피드백 내용은 10자 이상이어야 합니다.")
    val content: String,
    val type: FeedbackType
)



