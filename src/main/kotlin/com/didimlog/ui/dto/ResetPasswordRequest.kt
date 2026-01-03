package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 비밀번호 재설정 요청 DTO
 */
data class ResetPasswordRequest(
    @field:NotBlank(message = "재설정 코드는 필수입니다.")
    val resetCode: String,

    @field:NotBlank(message = "새 비밀번호는 필수입니다.")
    @field:Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    val newPassword: String
)





