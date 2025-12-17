package com.didimlog.ui.dto

import com.didimlog.domain.Feedback
import java.time.LocalDateTime

/**
 * 피드백 응답 DTO
 */
data class FeedbackResponse(
    val id: String,
    val writerId: String,
    val content: String,
    val type: String,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(feedback: Feedback): FeedbackResponse {
            return FeedbackResponse(
                id = feedback.id ?: "",
                writerId = feedback.writerId,
                content = feedback.content,
                type = feedback.type.value,
                status = feedback.status.value,
                createdAt = feedback.createdAt,
                updatedAt = feedback.updatedAt
            )
        }
    }
}





