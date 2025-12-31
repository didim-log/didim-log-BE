package com.didimlog.ui.dto

import com.didimlog.application.admin.PerformanceMetrics

/**
 * 성능 메트릭 응답 DTO
 */
data class PerformanceMetricsResponse(
    val rpm: Double,
    val averageResponseTime: Double,
    val timeRangeMinutes: Int
) {
    companion object {
        fun from(metrics: PerformanceMetrics): PerformanceMetricsResponse {
            return PerformanceMetricsResponse(
                rpm = metrics.rpm,
                averageResponseTime = metrics.averageResponseTime,
                timeRangeMinutes = metrics.timeRangeMinutes
            )
        }
    }
}

