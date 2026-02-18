package com.didimlog.application.admin

import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.roundToInt

/**
 * 성능 메트릭 서비스
 * PerformanceMonitoringInterceptor에서 수집한 데이터를 기반으로 성능 통계를 제공한다.
 */
@Service
class PerformanceMetricsService(
    private val performanceMonitoringInterceptor: PerformanceMonitoringInterceptor
) {

    /**
     * 성능 메트릭 데이터 클래스
     */
    data class PerformanceMetrics(
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
        val statusCodeSummary: List<StatusCodeSummary>,
        val rpmTimeSeries: List<TimeSeriesPoint>,
        val latencyTimeSeries: List<TimeSeriesPoint>,
        val errorRateTimeSeries: List<TimeSeriesPoint>
    )

    data class StatusCodeSummary(
        val statusCode: Int,
        val count: Long,
        val ratio: Double
    )

    /**
     * 시계열 포인트 데이터 클래스
     */
    data class TimeSeriesPoint(
        val timestamp: Long, // Unix timestamp (초)
        val value: Double
    )

    /**
     * 지정된 시간 범위의 성능 메트릭을 조회한다.
     *
     * @param minutes 조회할 시간 범위 (분)
     * @return 성능 메트릭 정보
     */
    fun getPerformanceMetrics(minutes: Int): PerformanceMetrics {
        val safeMinutes = minutes.coerceIn(1, MAX_MINUTES_RANGE)
        val recentMetrics = performanceMonitoringInterceptor.getRecentMetrics(safeMinutes)
        val totalRequests = recentMetrics.size.toLong()
        val errorRequests = recentMetrics.count { it.statusCode >= 400 }.toLong()
        val serverErrorRequests = recentMetrics.count { it.statusCode >= 500 }.toLong()
        val rpm = if (safeMinutes == 0) 0.0 else recentMetrics.size.toDouble() / safeMinutes
        val averageResponseTime = if (recentMetrics.isEmpty()) 0.0 else recentMetrics.map { it.responseTime }.average()
        val p95ResponseTime = percentile(recentMetrics.map { it.responseTime }, 95.0)
        val maxResponseTime = recentMetrics.maxOfOrNull { it.responseTime }?.toDouble() ?: 0.0
        val errorRate = ratioPercentage(errorRequests, totalRequests)
        val serverErrorRate = ratioPercentage(serverErrorRequests, totalRequests)
        val slowRequestCount = recentMetrics.count { it.responseTime >= SLOW_REQUEST_THRESHOLD_MILLIS }.toLong()
        val slowRequestRate = ratioPercentage(slowRequestCount, totalRequests)

        val metricsByMinute = recentMetrics.groupBy {
            val epochSecond = it.timestamp.epochSecond
            epochSecond - (epochSecond % 60)
        }
        val minuteBuckets = generateMinuteBuckets(safeMinutes)
        val rpmTimeSeries = minuteBuckets.map { minute ->
            TimeSeriesPoint(
                timestamp = minute,
                value = (metricsByMinute[minute]?.size ?: 0).toDouble()
            )
        }
        val latencyTimeSeries = minuteBuckets.map { minute ->
            val bucket = metricsByMinute[minute].orEmpty()
            val averageLatency = if (bucket.isEmpty()) 0.0 else bucket.map { it.responseTime }.average()
            TimeSeriesPoint(
                timestamp = minute,
                value = averageLatency
            )
        }
        val errorRateTimeSeries = minuteBuckets.map { minute ->
            val bucket = metricsByMinute[minute].orEmpty()
            val errorCount = bucket.count { it.statusCode >= 400 }
            val bucketErrorRate = ratioPercentage(errorCount.toLong(), bucket.size.toLong())
            TimeSeriesPoint(
                timestamp = minute,
                value = bucketErrorRate
            )
        }

        val statusCodeSummary = recentMetrics
            .groupingBy { it.statusCode }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .map { (statusCode, count) ->
                StatusCodeSummary(
                    statusCode = statusCode,
                    count = count.toLong(),
                    ratio = ratioPercentage(count.toLong(), totalRequests)
                )
            }

        return PerformanceMetrics(
            rpm = rpm,
            averageResponseTime = averageResponseTime,
            p95ResponseTime = p95ResponseTime,
            maxResponseTime = maxResponseTime,
            totalRequests = totalRequests,
            errorRequests = errorRequests,
            serverErrorRequests = serverErrorRequests,
            errorRate = errorRate,
            serverErrorRate = serverErrorRate,
            slowRequestRate = slowRequestRate,
            timeRangeMinutes = safeMinutes,
            statusCodeSummary = statusCodeSummary,
            rpmTimeSeries = rpmTimeSeries,
            latencyTimeSeries = latencyTimeSeries,
            errorRateTimeSeries = errorRateTimeSeries
        )
    }

    private fun generateMinuteBuckets(minutes: Int): List<Long> {
        val nowEpoch = Instant.now().epochSecond
        val currentMinute = nowEpoch - (nowEpoch % 60)
        return (0 until minutes).map { index ->
            val backward = (minutes - index - 1) * 60L
            currentMinute - backward
        }
    }

    private fun percentile(values: List<Long>, percentile: Double): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        val sorted = values.sorted()
        val rank = ((percentile / 100.0) * sorted.size).roundToInt().coerceIn(1, sorted.size)
        return sorted[rank - 1].toDouble()
    }

    private fun ratioPercentage(part: Long, total: Long): Double {
        if (total <= 0L) {
            return 0.0
        }
        return (part.toDouble() / total.toDouble()) * 100.0
    }

    companion object {
        private const val MAX_MINUTES_RANGE = 120
        private const val SLOW_REQUEST_THRESHOLD_MILLIS = 1000
    }
}
