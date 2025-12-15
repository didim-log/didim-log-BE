package com.didimlog.ui.dto

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 프로필 수정 요청 DTO
 */
data class UpdateProfileRequest(
    @field:Size(min = 2, max = 20, message = "닉네임은 2자 이상 20자 이하여야 합니다.")
    @field:Pattern(regexp = ".*\\S.*", message = "닉네임은 공백일 수 없습니다.")
    val nickname: String? = null,

    @field:Pattern(regexp = ".*\\S.*", message = "현재 비밀번호는 공백일 수 없습니다.")
    val currentPassword: String? = null,

    @field:Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    @field:Pattern(regexp = ".*\\S.*", message = "새 비밀번호는 공백일 수 없습니다.")
    val newPassword: String? = null
) {
    init {
        require(nickname == null || nickname.isNotBlank()) { "닉네임은 공백일 수 없습니다." }
        require(newPassword == null || currentPassword != null) {
            "비밀번호를 변경하려면 현재 비밀번호를 입력해야 합니다."
        }
    }
}

