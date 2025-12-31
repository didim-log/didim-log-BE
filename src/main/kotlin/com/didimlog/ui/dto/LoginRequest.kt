package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 로그인 요청 DTO
 * BOJ ID와 비밀번호만 필요합니다.
 */
data class LoginRequest(
    @field:NotBlank(message = "BOJ ID는 필수입니다.")
    val bojId: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    val password: String
)



