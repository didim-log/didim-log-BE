package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 슈퍼 관리자 생성 요청 DTO
 */
data class SuperAdminRequest(
    @field:NotBlank(message = "BOJ ID는 필수입니다.")
    val bojId: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    val password: String,

    @field:NotBlank(message = "관리자 키는 필수입니다.")
    val adminKey: String
)

