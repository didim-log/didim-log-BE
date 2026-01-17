package com.didimlog.ui.dto

import com.didimlog.domain.enums.TemplateType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class StaticTemplateRequest(
    @field:NotBlank(message = "code는 필수입니다.")
    val code: String,
    @field:NotBlank(message = "problemId는 필수입니다.")
    val problemId: String,
    @field:NotNull(message = "isSuccess는 필수입니다.")
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val solveTime: String? = null, // 풀이 소요 시간 (예: "15m 30s" 또는 초 단위 문자열)
    val templateType: TemplateType? = TemplateType.SIMPLE // 템플릿 타입 (기본값: SIMPLE)
)
















