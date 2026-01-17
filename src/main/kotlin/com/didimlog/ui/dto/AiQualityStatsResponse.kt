package com.didimlog.ui.dto

import com.didimlog.application.admin.AiQualityService

/**
 * AI 품질 통계 응답 DTO
 */
data class AiQualityStatsResponse(
    val totalFeedbackCount: Long,
    val positiveRate: Double,
    val negativeReasons: Map<String, Int>,
    val recentNegativeLogs: List<RecentNegativeLogResponse>
) {
    companion object {
        fun from(stats: AiQualityService.AiQualityStats): AiQualityStatsResponse {
            return AiQualityStatsResponse(
                totalFeedbackCount = stats.totalFeedbackCount,
                positiveRate = stats.positiveRate,
                negativeReasons = stats.negativeReasons,
                recentNegativeLogs = stats.recentNegativeLogs.map { log ->
                    RecentNegativeLogResponse(
                        id = log.id,
                        aiReview = log.aiReview,
                        codeSnippet = log.codeSnippet
                    )
                }
            )
        }
    }
}

/**
 * 최근 부정 평가 로그 응답 DTO
 */
data class RecentNegativeLogResponse(
    val id: String,
    val aiReview: String,
    val codeSnippet: String
)













