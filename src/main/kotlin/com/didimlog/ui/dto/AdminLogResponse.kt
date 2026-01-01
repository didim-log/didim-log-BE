package com.didimlog.ui.dto

import com.didimlog.domain.Log
import java.time.LocalDateTime

/**
 * 관리자용 로그 응답 DTO
 */
data class AdminLogResponse(
    val id: String,
    val title: String,
    val content: String,
    val code: String,
    val bojId: String?,
    val isSuccess: Boolean?,
    val createdAt: LocalDateTime,
    val aiReview: String?,
    val aiReviewStatus: String?,
    val aiReviewDurationMillis: Long?,
    val aiReviewDurationSeconds: Double?
) {
    companion object {
        fun from(log: Log): AdminLogResponse {
            val durationSeconds = log.aiReviewDurationMillis?.div(1000.0)?.let {
                String.format("%.2f", it).toDouble()
            }
            return AdminLogResponse(
                id = log.id ?: throw IllegalStateException("로그 ID가 없습니다."),
                title = log.title.value,
                content = log.content.value,
                code = log.code.value,
                bojId = log.bojId?.value,
                isSuccess = log.isSuccess,
                createdAt = log.createdAt,
                aiReview = log.aiReview?.value,
                aiReviewStatus = log.aiReviewStatus?.name,
                aiReviewDurationMillis = log.aiReviewDurationMillis,
                aiReviewDurationSeconds = durationSeconds
            )
        }
    }
}

