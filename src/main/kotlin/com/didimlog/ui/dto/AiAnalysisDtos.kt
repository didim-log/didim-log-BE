package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class AiAnalyzeRequest(
    @field:NotBlank(message = "code는 필수입니다.")
    val code: String,
    @field:NotBlank(message = "problemId는 필수입니다.")
    val problemId: String,
    @field:NotNull(message = "isSuccess는 필수입니다.")
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

data class AiAnalyzeResponse(
    val markdown: String
)

