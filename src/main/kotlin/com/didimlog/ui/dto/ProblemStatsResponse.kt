package com.didimlog.ui.dto

import com.didimlog.application.admin.ProblemStatsService

/**
 * 관리자 문제 통계 응답 DTO
 */
data class ProblemStatsResponse(
    val totalCount: Long,
    val minProblemId: Int?,
    val maxProblemId: Int?,
    val minNullDescriptionHtmlProblemId: Int?,
    val minNullLanguageProblemId: Int?
) {
    companion object {
        fun from(stats: ProblemStatsService.ProblemStats): ProblemStatsResponse {
            return ProblemStatsResponse(
                totalCount = stats.totalCount,
                minProblemId = stats.minProblemId,
                maxProblemId = stats.maxProblemId,
                minNullDescriptionHtmlProblemId = stats.minNullDescriptionHtmlProblemId,
                minNullLanguageProblemId = stats.minNullLanguageProblemId
            )
        }
    }
}
