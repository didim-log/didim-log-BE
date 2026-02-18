package com.didimlog.ui.dto

import com.didimlog.application.admin.PerformanceMetricsService

/**
 * 성능 메트릭 응답 DTO
 */
data class PerformanceMetricsResponse(
    val rpm: Double,
    val averageResponseTime: Double,
    val p95ResponseTime: Double,
    val maxResponseTime: Double,
    val totalRequests: Long,
    val errorRequests: Long,
    val serverErrorRequests: Long,
    val errorRate: Double,
    val serverErrorRate: Double,
    val slowRequestRate: Double,
    val timeRangeMinutes: Int,
    val statusCodeSummary: List<StatusCodeSummaryResponse>,
    val rpmTimeSeries: List<TimeSeriesPointResponse>,
    val latencyTimeSeries: List<TimeSeriesPointResponse>,
    val errorRateTimeSeries: List<TimeSeriesPointResponse>
) {
    companion object {
        fun from(metrics: PerformanceMetricsService.PerformanceMetrics): PerformanceMetricsResponse {
            return PerformanceMetricsResponse(
                rpm = metrics.rpm,
                averageResponseTime = metrics.averageResponseTime,
                p95ResponseTime = metrics.p95ResponseTime,
                maxResponseTime = metrics.maxResponseTime,
                totalRequests = metrics.totalRequests,
                errorRequests = metrics.errorRequests,
                serverErrorRequests = metrics.serverErrorRequests,
                errorRate = metrics.errorRate,
                serverErrorRate = metrics.serverErrorRate,
                slowRequestRate = metrics.slowRequestRate,
                timeRangeMinutes = metrics.timeRangeMinutes,
                statusCodeSummary = metrics.statusCodeSummary.map { StatusCodeSummaryResponse.from(it) },
                rpmTimeSeries = metrics.rpmTimeSeries.map { TimeSeriesPointResponse.from(it) },
                latencyTimeSeries = metrics.latencyTimeSeries.map { TimeSeriesPointResponse.from(it) },
                errorRateTimeSeries = metrics.errorRateTimeSeries.map { TimeSeriesPointResponse.from(it) }
            )
        }
    }
}

data class StatusCodeSummaryResponse(
    val statusCode: Int,
    val count: Long,
    val ratio: Double
) {
    companion object {
        fun from(summary: PerformanceMetricsService.StatusCodeSummary): StatusCodeSummaryResponse {
            return StatusCodeSummaryResponse(
                statusCode = summary.statusCode,
                count = summary.count,
                ratio = summary.ratio
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
        fun from(point: PerformanceMetricsService.TimeSeriesPoint): TimeSeriesPointResponse {
            return TimeSeriesPointResponse(
                timestamp = point.timestamp,
                value = point.value
            )
        }
    }
}
