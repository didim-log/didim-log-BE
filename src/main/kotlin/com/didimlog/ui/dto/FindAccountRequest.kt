package com.didimlog.ui.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

/**
 * 계정 찾기 요청 DTO
 * 이메일로 가입된 소셜 제공자를 조회한다.
 */
data class FindAccountRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    val email: String
)




