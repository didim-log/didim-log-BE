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
     * @return 성능 메트릭 정보
     */
    fun getPerformanceMetrics(minutes: Int = 30): PerformanceMetrics {
        val rpm = performanceMonitoringInterceptor.calculateRPM(minutes)
        val averageResponseTime = performanceMonitoringInterceptor.calculateAverageResponseTime(minutes)

        return PerformanceMetrics(
            rpm = rpm,
            averageResponseTime = averageResponseTime,
            timeRangeMinutes = minutes
        )
    }
}

/**
 * 성능 메트릭 정보
 */
data class PerformanceMetrics(
    val rpm: Double, // 분당 요청 수
    val averageResponseTime: Double, // 평균 응답 시간 (밀리초)
    val timeRangeMinutes: Int // 조회한 시간 범위 (분)
)

