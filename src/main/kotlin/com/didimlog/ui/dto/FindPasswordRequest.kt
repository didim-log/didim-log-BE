package com.didimlog.ui.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class FindPasswordRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "유효한 이메일 형식이 아닙니다.")
    val email: String,
    @field:NotBlank(message = "BOJ ID는 필수입니다.")
    val bojId: String
)



