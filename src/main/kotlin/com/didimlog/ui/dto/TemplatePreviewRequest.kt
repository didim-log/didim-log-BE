package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 템플릿 미리보기 요청 DTO
 */
data class TemplatePreviewRequest(
    @field:NotBlank(message = "템플릿 내용은 필수입니다.")
    val templateContent: String,

    @field:NotNull(message = "문제 ID는 필수입니다.")
    val problemId: Long
)
