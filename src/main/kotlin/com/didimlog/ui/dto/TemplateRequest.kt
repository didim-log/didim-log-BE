package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 템플릿 생성/수정 요청 DTO
 */
data class TemplateRequest(
    @field:NotBlank(message = "템플릿 제목은 필수입니다.")
    @field:Size(max = 100, message = "템플릿 제목은 100자 이하여야 합니다.")
    val title: String,

    @field:NotBlank(message = "템플릿 내용은 필수입니다.")
    @field:Size(max = 10000, message = "템플릿 내용은 10000자 이하여야 합니다.")
    val content: String
)
