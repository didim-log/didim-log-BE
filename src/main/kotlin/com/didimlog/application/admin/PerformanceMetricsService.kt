package com.didimlog.application.admin

import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import org.springframework.stereotype.Service

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
        val timeRangeMinutes: Int,
        val rpmTimeSeries: List<TimeSeriesPoint>,
        val latencyTimeSeries: List<TimeSeriesPoint>
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
        val rpm = performanceMonitoringInterceptor.calculateRPM(minutes)
        val averageResponseTime = performanceMonitoringInterceptor.calculateAverageResponseTime(minutes)
        
        // 시계열 데이터 생성 (간단한 구현)
        val rpmTimeSeries = generateTimeSeries(minutes) { rpm }
        val latencyTimeSeries = generateTimeSeries(minutes) { averageResponseTime }
        
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
     */
    private fun generateTimeSeries(minutes: Int, valueProvider: () -> Double): List<TimeSeriesPoint> {
        val now = System.currentTimeMillis() / 1000 // Unix timestamp (초)
        val intervalSeconds = 60L // 1분 간격
        val points = mutableListOf<TimeSeriesPoint>()
        
        val value = valueProvider()
        
        for (i in 0 until minutes) {
            val timestamp = now - (minutes - i - 1) * intervalSeconds
            points.add(TimeSeriesPoint(timestamp = timestamp, value = value))
        }
        
        return points
    }
}
