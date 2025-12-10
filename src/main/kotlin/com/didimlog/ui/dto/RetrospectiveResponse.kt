package com.didimlog.ui.dto

import com.didimlog.domain.Retrospective
import java.time.LocalDateTime

/**
 * 회고 응답 DTO
 */
data class RetrospectiveResponse(
    val id: String, // Retrospective 엔티티의 DB ID (@Id 필드)
    val studentId: String, // Student 엔티티의 DB ID (@Id 필드)
    val problemId: String,
    val content: String,
    val summary: String?, // 한 줄 요약
    val createdAt: LocalDateTime,
    val isBookmarked: Boolean,
    val mainCategory: String?,
    val solutionResult: String?, // 풀이 결과 (SUCCESS/FAIL)
    val solvedCategory: String? // 사용자가 선택한 풀이 전략 태그
) {
    companion object {
        fun from(retrospective: Retrospective): RetrospectiveResponse {
            return RetrospectiveResponse(
                id = retrospective.id ?: "",
                studentId = retrospective.studentId,
                problemId = retrospective.problemId,
                content = retrospective.content,
                summary = retrospective.summary,
                createdAt = retrospective.createdAt,
                isBookmarked = retrospective.isBookmarked,
                mainCategory = retrospective.mainCategory?.name,
                solutionResult = retrospective.solutionResult?.name,
                solvedCategory = retrospective.solvedCategory
            )
        }
    }
}


