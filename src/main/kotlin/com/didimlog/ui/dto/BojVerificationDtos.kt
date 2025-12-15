package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank

data class BojCodeIssueResponse(
    val sessionId: String,
    val code: String,
    val expiresInSeconds: Long
)

data class BojVerifyRequest(
    @field:NotBlank(message = "sessionId는 필수입니다.")
    val sessionId: String,
    @field:NotBlank(message = "bojId는 필수입니다.")
    val bojId: String
)

data class BojVerifyResponse(
    val verified: Boolean = true
)

