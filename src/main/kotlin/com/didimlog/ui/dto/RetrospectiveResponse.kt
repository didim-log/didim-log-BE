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
    val createdAt: LocalDateTime,
    val isBookmarked: Boolean,
    val mainCategory: String?
) {
    companion object {
        fun from(retrospective: Retrospective): RetrospectiveResponse {
            return RetrospectiveResponse(
                id = retrospective.id ?: "",
                studentId = retrospective.studentId,
                problemId = retrospective.problemId,
                content = retrospective.content,
                createdAt = retrospective.createdAt,
                isBookmarked = retrospective.isBookmarked,
                mainCategory = retrospective.mainCategory?.name
            )
        }
    }
}


