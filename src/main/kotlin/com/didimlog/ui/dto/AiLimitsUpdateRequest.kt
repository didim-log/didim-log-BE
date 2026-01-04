package com.didimlog.ui.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * AI 사용량 제한 업데이트 요청 DTO
 */
data class AiLimitsUpdateRequest(
    @field:NotNull(message = "globalLimit은 필수입니다.")
    @field:Min(value = 1, message = "globalLimit은 1 이상이어야 합니다.")
    val globalLimit: Int,
    
    @field:NotNull(message = "userLimit은 필수입니다.")
    @field:Min(value = 1, message = "userLimit은 1 이상이어야 합니다.")
    val userLimit: Int
)









