package com.didimlog.ui.dto

/**
 * 랭킹 응답 DTO
 */
data class LeaderboardResponse(
    val rank: Int, // 순위 (1부터 시작)
    val nickname: String,
    val tier: String, // 티어명 (예: "GOLD", "SILVER")
    val tierLevel: Int, // 티어 레벨 (Solved.ac 레벨 대표값)
    val rating: Int, // Solved.ac Rating (점수)
    val retrospectiveCount: Long, // 작성한 회고 수
    val consecutiveSolveDays: Int, // 연속 풀이 일수
    val profileImageUrl: String? = null // 프로필 이미지 URL (향후 확장용)
) {
    companion object {
        fun from(
            student: com.didimlog.domain.Student,
            rank: Int,
            retrospectiveCount: Long
        ): LeaderboardResponse {
            return LeaderboardResponse(
                rank = rank,
                nickname = student.nickname.value,
                tier = student.currentTier.name,
                tierLevel = student.solvedAcTierLevel.value,
                rating = student.rating,
                retrospectiveCount = retrospectiveCount,
                consecutiveSolveDays = student.consecutiveSolveDays,
                profileImageUrl = null // 향후 프로필 이미지 기능 추가 시 구현
            )
        }
    }
}

