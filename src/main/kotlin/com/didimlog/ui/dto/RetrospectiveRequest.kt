package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 회고 작성 요청 DTO
 */
data class RetrospectiveRequest(
    @field:NotBlank(message = "회고 내용은 필수입니다.")
    @field:Size(min = 10, message = "회고 내용은 10자 이상이어야 합니다.")
    val content: String,
    
    @field:Size(max = 200, message = "한 줄 요약은 200자 이하여야 합니다.")
    val summary: String? = null // 한 줄 요약 (선택사항)
)


