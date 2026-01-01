package com.didimlog.ui.dto

import com.didimlog.domain.Log
import jakarta.validation.constraints.NotBlank

data class AiReviewResponse(
    val review: String,
    val cached: Boolean
)

/**
 * 로그 생성 요청 DTO
 */
data class LogCreateRequest(
    @field:NotBlank(message = "제목은 필수입니다.")
    val title: String,

    @field:NotBlank(message = "내용은 필수입니다.")
    val content: String,

    @field:NotBlank(message = "코드는 필수입니다.")
    val code: String
)

/**
 * 로그 생성 응답 DTO
 */
data class LogResponse(
    val id: String
) {
    companion object {
        fun from(log: Log): LogResponse {
            return LogResponse(
                id = log.id ?: throw IllegalStateException("로그 ID가 없습니다.")
            )
        }
    }
}


