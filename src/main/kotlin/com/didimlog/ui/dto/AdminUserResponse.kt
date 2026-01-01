package com.didimlog.ui.dto

import com.didimlog.domain.Student
import java.time.LocalDateTime

/**
 * 관리자용 회원 정보 응답 DTO
 */
data class AdminUserResponse(
    val id: String,
    val nickname: String,
    val bojId: String?,
    val email: String?,
    val provider: String,
    val role: String,
    val rating: Int,
    val currentTier: String,
    val consecutiveSolveDays: Int,
    val termsAgreed: Boolean,
    val solvedCount: Long, // 해결한 문제 수 (SUCCESS인 Solution 개수)
    val retrospectiveCount: Long, // 작성한 회고 수
    val createdAt: LocalDateTime? = null // MongoDB _id에서 추출 가능하지만 명시적으로 포함
) {
    companion object {
        fun from(
            student: Student,
            solvedCount: Long,
            retrospectiveCount: Long
        ): AdminUserResponse {
            return AdminUserResponse(
                id = student.id ?: "",
                nickname = student.nickname.value,
                bojId = student.bojId?.value,
                email = student.email,
                provider = student.provider.value,
                role = student.role.value,
                rating = student.rating,
                currentTier = student.currentTier.name,
                consecutiveSolveDays = student.consecutiveSolveDays,
                termsAgreed = student.termsAgreed, // 기본값 false로 처리됨
                solvedCount = solvedCount,
                retrospectiveCount = retrospectiveCount
            )
        }
    }
}


