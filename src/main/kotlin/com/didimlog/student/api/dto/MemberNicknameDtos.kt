package com.didimlog.student.api.dto

import jakarta.validation.constraints.NotBlank

data class UpdateMyNicknameRequest(
    @field:NotBlank(message = "닉네임은 필수입니다.")
    val nickname: String
)
