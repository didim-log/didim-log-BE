package com.didimlog.ui.dto

import com.didimlog.application.ai.AiSectionType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class AiAnalyzeRequest(
    @field:NotBlank(message = "code는 필수입니다.")
    val code: String,
    @field:NotBlank(message = "problemId는 필수입니다.")
    val problemId: String,
    @field:NotNull(message = "sectionType은 필수입니다.")
    val sectionType: AiSectionType,
    @field:NotNull(message = "isSuccess는 필수입니다.")
    val isSuccess: Boolean
)

data class AiAnalyzeResponse(
    val sectionType: AiSectionType,
    val markdown: String
)

