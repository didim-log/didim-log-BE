package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 가입 마무리 요청 DTO
 * 소셜 로그인 후 약관 동의 및 닉네임 설정을 완료한다.
 */
data class SignupFinalizeRequest(
    @field:NotBlank(message = "닉네임은 필수입니다.")
    val nickname: String,

    @field:NotNull(message = "약관 동의는 필수입니다.")
    val termsAgreed: Boolean
)
