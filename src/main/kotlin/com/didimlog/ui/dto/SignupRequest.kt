package com.didimlog.ui.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 회원가입 요청 DTO
 * BOJ ID, 비밀번호, 이메일이 모두 필요합니다.
 */
data class SignupRequest(
    @field:NotBlank(message = "BOJ ID는 필수입니다.")
    val bojId: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    val password: String,

    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "유효한 이메일 형식이 아닙니다.")
    val email: String
)




