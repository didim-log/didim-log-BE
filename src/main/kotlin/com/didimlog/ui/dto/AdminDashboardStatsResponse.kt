package com.didimlog.ui.dto

import com.didimlog.application.admin.AdminDashboardStats

/**
 * 관리자 대시보드 통계 응답 DTO
 */
data class AdminDashboardStatsResponse(
    val totalUsers: Long,
    val todaySignups: Long,
    val totalSolvedProblems: Long,
    val todayRetrospectives: Long,
    val aiMetrics: AiMetricsResponse
) {
    companion object {
        fun from(stats: AdminDashboardStats): AdminDashboardStatsResponse {
            return AdminDashboardStatsResponse(
                totalUsers = stats.totalUsers,
                todaySignups = stats.todaySignups,
                totalSolvedProblems = stats.totalSolvedProblems,
                todayRetrospectives = stats.todayRetrospectives,
                aiMetrics = AiMetricsResponse.from(stats.aiMetrics)
            )
        }
    }
}

/**
 * AI 생성 통계 응답 DTO
 */
data class AiMetricsResponse(
    val averageDurationMillis: Long?, // 평균 AI 생성 시간 (밀리초)
    val averageDurationSeconds: Double?, // 평균 AI 생성 시간 (초, 소수점 2자리)
    val totalGeneratedCount: Long, // 총 생성된 AI 리뷰 수
    val timeoutCount: Long, // 타임아웃된 리뷰 수
    val timeoutRate: Double // 타임아웃 비율 (0.0 ~ 1.0)
) {
    companion object {
        fun from(metrics: com.didimlog.application.admin.AiMetrics): AiMetricsResponse {
            val avgSeconds = metrics.averageDurationMillis?.div(1000.0)?.let { 
                String.format("%.2f", it).toDouble() 
            }
            val timeoutRate = calculateTimeoutRate(metrics.totalGeneratedCount, metrics.timeoutCount)
            
            return AiMetricsResponse(
                averageDurationMillis = metrics.averageDurationMillis,
                averageDurationSeconds = avgSeconds,
                totalGeneratedCount = metrics.totalGeneratedCount,
                timeoutCount = metrics.timeoutCount,
                timeoutRate = timeoutRate
            )
        }

        private fun calculateTimeoutRate(totalGeneratedCount: Long, timeoutCount: Long): Double {
            if (totalGeneratedCount > 0) {
                return timeoutCount.toDouble() / totalGeneratedCount.toDouble()
            }
            return 0.0
        }
    }
}


