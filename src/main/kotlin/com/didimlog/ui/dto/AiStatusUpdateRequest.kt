package com.didimlog.ui.dto

import jakarta.validation.constraints.NotNull

/**
 * AI 서비스 상태 업데이트 요청 DTO
 */
data class AiStatusUpdateRequest(
    @field:NotNull(message = "enabled는 필수입니다.")
    val enabled: Boolean
)


