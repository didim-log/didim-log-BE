package com.didimlog.ui.dto

/**
 * 랭킹 응답 DTO
 */
data class RankingResponse(
    val rank: Int, // 순위 (1등, 2등...)
    val nickname: String, // 사용자 닉네임
    val tier: String, // 티어 정보 (Enum name)
    val rating: Int, // 점수
    val solvedCount: Int, // 푼 문제 수
    val bio: String? = null // 한줄 소개 (없으면 생략)
) {
    companion object {
        fun from(student: com.didimlog.domain.Student, rank: Int): RankingResponse {
            return RankingResponse(
                rank = rank,
                nickname = student.nickname.value,
                tier = student.currentTier.name,
                rating = student.rating,
                solvedCount = student.solutions.getAll().size,
                bio = null // 향후 한줄 소개 기능 추가 시 구현
            )
        }
    }
}


