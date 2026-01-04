package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank

/**
 * 명언 생성 요청 DTO
 */
data class QuoteCreateRequest(
    @field:NotBlank(message = "명언 내용은 필수입니다.")
    val content: String,
    @field:NotBlank(message = "저자명은 필수입니다.")
    val author: String
)










