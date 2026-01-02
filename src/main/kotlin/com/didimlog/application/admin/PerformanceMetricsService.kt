package com.didimlog.application.admin

import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 성능 메트릭 서비스
 * PerformanceMonitoringInterceptor에서 수집한 데이터를 기반으로 성능 통계를 제공한다.
 */
@Service
class PerformanceMetricsService(
    private val performanceMonitoringInterceptor: PerformanceMonitoringInterceptor
) {

    /**
     * 최근 지정된 시간(분) 동안의 성능 메트릭을 조회한다.
     *
     * @param minutes 조회할 시간 범위 (분)
     * @return 성능 메트릭
     */
    fun getPerformanceMetrics(minutes: Int): PerformanceMetrics {
        val recentMetrics = performanceMonitoringInterceptor.getRecentMetrics(minutes)
        val rpm = performanceMonitoringInterceptor.calculateRPM(minutes)
        val averageResponseTime = performanceMonitoringInterceptor.calculateAverageResponseTime(minutes)

        // 시계열 데이터 생성 (최대 30개 포인트)
        val rpmTimeSeries = generateTimeSeries(recentMetrics, minutes) { 1.0 }
        val latencyTimeSeries = generateTimeSeries(recentMetrics, minutes) { it.responseTime.toDouble() }

        return PerformanceMetrics(
            rpm = rpm,
            averageResponseTime = averageResponseTime,
            timeRangeMinutes = minutes,
            rpmTimeSeries = rpmTimeSeries,
            latencyTimeSeries = latencyTimeSeries
        )
    }

    /**
     * 시계열 데이터를 생성한다.
     * 메트릭을 시간 구간별로 집계하여 최대 30개 포인트를 생성한다.
     *
     * @param metrics 원본 메트릭 리스트
     * @param minutes 시간 범위 (분)
     * @param valueExtractor 각 메트릭에서 값을 추출하는 함수
     * @return 시계열 포인트 리스트 (최대 30개)
     */
    private fun generateTimeSeries(
        metrics: List<com.didimlog.global.interceptor.RequestMetric>,
        minutes: Int,
        valueExtractor: (com.didimlog.global.interceptor.RequestMetric) -> Double
    ): List<TimeSeriesPoint> {
        if (metrics.isEmpty()) {
            return emptyList()
        }

        val maxPoints = 30
        val intervalSeconds = (minutes * 60) / maxPoints
        val now = Instant.now()
        val cutoffTime = now.minusSeconds(minutes * 60L)

        val timeSeriesMap = mutableMapOf<Long, MutableList<Double>>()

        metrics.forEach { metric ->
            val secondsSinceCutoff = java.time.Duration.between(cutoffTime, metric.timestamp).seconds
            val bucketIndex = secondsSinceCutoff / intervalSeconds
            val bucketTime = cutoffTime.epochSecond + (bucketIndex * intervalSeconds)

            timeSeriesMap.getOrPut(bucketTime) { mutableListOf() }.add(valueExtractor(metric))
        }

        return timeSeriesMap.entries
            .sortedBy { it.key }
            .take(maxPoints)
            .map { (timestamp, values) ->
                TimeSeriesPoint(
                    timestamp = timestamp,
                    value = values.average()
                )
            }
    }
}

/**
 * 성능 메트릭 데이터 클래스
 */
data class PerformanceMetrics(
    val rpm: Double,
    val averageResponseTime: Double,
    val timeRangeMinutes: Int,
    val rpmTimeSeries: List<TimeSeriesPoint>,
    val latencyTimeSeries: List<TimeSeriesPoint>
)

/**
 * 시계열 포인트 데이터 클래스
 */
data class TimeSeriesPoint(
    val timestamp: Long,
    val value: Double
)
