package com.didimlog.ui.dto

import com.didimlog.application.admin.PerformanceMetrics

/**
 * 성능 메트릭 응답 DTO
 */
data class PerformanceMetricsResponse(
    val rpm: Double,
    val averageResponseTime: Double,
    val timeRangeMinutes: Int,
    val rpmTimeSeries: List<TimeSeriesPointResponse>,
    val latencyTimeSeries: List<TimeSeriesPointResponse>
) {
    companion object {
        fun from(metrics: PerformanceMetrics): PerformanceMetricsResponse {
            return PerformanceMetricsResponse(
                rpm = metrics.rpm,
                averageResponseTime = metrics.averageResponseTime,
                timeRangeMinutes = metrics.timeRangeMinutes,
                rpmTimeSeries = metrics.rpmTimeSeries.map { TimeSeriesPointResponse.from(it) },
                latencyTimeSeries = metrics.latencyTimeSeries.map { TimeSeriesPointResponse.from(it) }
            )
        }
    }
}

/**
 * Time Series 포인트 응답 DTO
 */
data class TimeSeriesPointResponse(
    val timestamp: Long,
    val value: Double
) {
    companion object {
        fun from(point: com.didimlog.application.admin.TimeSeriesPoint): TimeSeriesPointResponse {
            return TimeSeriesPointResponse(
                timestamp = point.timestamp,
                value = point.value
            )
        }
    }
}

