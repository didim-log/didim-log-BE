package com.didimlog.ui.dto

/**
 * 관리자 사용자 강제 수정 요청 DTO
 * - 제공된 필드만 선택적으로 수정한다. (Dynamic Update)
 */
data class AdminUserUpdateDto(
    val role: String? = null, // ROLE_USER, ROLE_ADMIN
    val nickname: String? = null,
    val bojId: String? = null
)

