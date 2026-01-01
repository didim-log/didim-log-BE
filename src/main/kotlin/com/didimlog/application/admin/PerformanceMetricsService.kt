package com.didimlog.application.admin

import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import org.springframework.stereotype.Service

/**
 * 성능 메트릭 서비스
 * 관리자 대시보드에서 사용할 성능 데이터를 제공한다.
 */
@Service
class PerformanceMetricsService(
    private val performanceMonitoringInterceptor: PerformanceMonitoringInterceptor
) {

    /**
     * 성능 메트릭을 조회한다.
     *
     * @param minutes 조회할 시간 범위 (분, 기본값: 30)
     * @return 성능 메트릭 정보 (Time Series 포함)
     */
    fun getPerformanceMetrics(minutes: Int = 30): PerformanceMetrics {
        val recentMetrics = performanceMonitoringInterceptor.getRecentMetrics(minutes)
        
        val rpm = calculateRPM(recentMetrics, minutes)
        val averageResponseTime = calculateAverageResponseTime(recentMetrics)

        // Time Series 데이터 생성 (최근 30개 데이터 포인트)
        val rpmTimeSeries = generateRPMTimeSeries(recentMetrics)
        val latencyTimeSeries = generateLatencyTimeSeries(recentMetrics)

        return PerformanceMetrics(
            rpm = rpm,
            averageResponseTime = averageResponseTime,
            timeRangeMinutes = minutes,
            rpmTimeSeries = rpmTimeSeries,
            latencyTimeSeries = latencyTimeSeries
        )
    }

    private fun calculateRPM(recentMetrics: List<com.didimlog.global.interceptor.RequestMetric>, minutes: Int): Double {
        if (recentMetrics.isEmpty()) {
            return 0.0
        }
        return recentMetrics.size.toDouble() / minutes
    }

    private fun calculateAverageResponseTime(recentMetrics: List<com.didimlog.global.interceptor.RequestMetric>): Double {
        if (recentMetrics.isEmpty()) {
            return 0.0
        }
        return recentMetrics.map { it.responseTime }.average()
    }

    /**
     * RPM Time Series 데이터를 생성한다.
     */
    private fun generateRPMTimeSeries(metrics: List<com.didimlog.global.interceptor.RequestMetric>): List<TimeSeriesPoint> {
        if (metrics.isEmpty()) {
            return emptyList()
        }

        // 1분 단위로 그룹화
        val grouped = metrics.groupBy { metric ->
            metric.timestamp.epochSecond / 60 // 분 단위로 그룹화
        }

        val sortedKeys = grouped.keys.sorted()
        val maxPoints = 30 // 최대 30개 포인트
        val step = maxOf(1, sortedKeys.size / maxPoints)

        return sortedKeys.filterIndexed { index, _ -> index % step == 0 }
            .take(maxPoints)
            .map { minuteKey ->
                val minuteMetrics = grouped[minuteKey]!!
                val rpm = minuteMetrics.size.toDouble() // 해당 분의 요청 수
                val timestamp = minuteKey * 60L // 초 단위로 변환
                TimeSeriesPoint(
                    timestamp = timestamp,
                    value = rpm
                )
            }
    }

    /**
     * Latency Time Series 데이터를 생성한다.
     */
    private fun generateLatencyTimeSeries(metrics: List<com.didimlog.global.interceptor.RequestMetric>): List<TimeSeriesPoint> {
        if (metrics.isEmpty()) {
            return emptyList()
        }

        // 1분 단위로 그룹화하여 평균 응답 시간 계산
        val grouped = metrics.groupBy { metric ->
            metric.timestamp.epochSecond / 60 // 분 단위로 그룹화
        }

        val sortedKeys = grouped.keys.sorted()
        val maxPoints = 30 // 최대 30개 포인트
        val step = maxOf(1, sortedKeys.size / maxPoints)

        return sortedKeys.filterIndexed { index, _ -> index % step == 0 }
            .take(maxPoints)
            .map { minuteKey ->
                val minuteMetrics = grouped[minuteKey]!!
                val avgLatency = minuteMetrics.map { it.responseTime }.average()
                val timestamp = minuteKey * 60L // 초 단위로 변환
                TimeSeriesPoint(
                    timestamp = timestamp,
                    value = avgLatency
                )
            }
    }
}

/**
 * 성능 메트릭 정보
 */
data class PerformanceMetrics(
    val rpm: Double, // 분당 요청 수
    val averageResponseTime: Double, // 평균 응답 시간 (밀리초)
    val timeRangeMinutes: Int, // 조회한 시간 범위 (분)
    val rpmTimeSeries: List<TimeSeriesPoint>, // RPM Time Series 데이터
    val latencyTimeSeries: List<TimeSeriesPoint> // Latency Time Series 데이터
)

/**
 * Time Series 데이터 포인트
 */
data class TimeSeriesPoint(
    val timestamp: Long, // Unix timestamp (초)
    val value: Double    // 값
)

