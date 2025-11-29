package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

/**
 * 문제 풀이 제출 요청 DTO
 */
data class SolutionSubmitRequest(
    @field:NotBlank(message = "문제 ID는 필수입니다.")
    val problemId: String,

    @field:NotNull(message = "풀이 시간은 필수입니다.")
    @field:Positive(message = "풀이 시간은 0보다 커야 합니다.")
    val timeTaken: Long,

    @field:NotNull(message = "성공 여부는 필수입니다.")
    val isSuccess: Boolean
)

