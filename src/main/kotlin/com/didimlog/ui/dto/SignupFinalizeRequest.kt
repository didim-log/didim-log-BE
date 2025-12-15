package com.didimlog.ui.dto

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 가입 마무리 요청 DTO
 * 소셜 로그인 후 약관 동의 및 닉네임 설정을 완료한다.
 */
data class SignupFinalizeRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,

    @field:NotBlank(message = "프로바이더는 필수입니다.")
    val provider: String,

    @field:NotBlank(message = "프로바이더 ID는 필수입니다.")
    val providerId: String,

    @field:NotBlank(message = "닉네임은 필수입니다.")
    val nickname: String,

    val bojId: String? = null, // BOJ ID (선택사항, 나중에 연동 가능)

    @field:NotNull(message = "약관 동의는 필수입니다.")
    @field:JsonAlias("isAgreedToTerms")
    val termsAgreed: Boolean
)
