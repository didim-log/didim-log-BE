package com.didimlog.ui.dto

import com.didimlog.domain.Student

/**
 * 문제 풀이 제출 응답 DTO
 */
data class SolutionSubmitResponse(
    val message: String,
    val currentTier: String,
    val currentTierLevel: Int
) {
    companion object {
        fun from(student: Student): SolutionSubmitResponse {
            return SolutionSubmitResponse(
                message = "문제 풀이 결과가 저장되었습니다.",
                currentTier = student.tier().name,
                currentTierLevel = student.tier().value
            )
        }
    }
}
