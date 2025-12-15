package com.didimlog.ui.dto

import jakarta.validation.constraints.Pattern

/**
 * 관리자 사용자 강제 수정 요청 DTO
 * - 제공된 필드만 선택적으로 수정한다. (Dynamic Update)
 */
data class AdminUserUpdateDto(
    @field:Pattern(
        regexp = ".*\\S.*",
        message = "role은 공백일 수 없습니다."
    )
    val role: String? = null, // ROLE_USER, ROLE_ADMIN

    @field:Pattern(
        regexp = ".*\\S.*",
        message = "nickname은 공백일 수 없습니다."
    )
    val nickname: String? = null,

    @field:Pattern(
        regexp = ".*\\S.*",
        message = "bojId는 공백일 수 없습니다."
    )
    val bojId: String? = null
)

