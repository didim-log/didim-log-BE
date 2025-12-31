package com.didimlog.global.interceptor

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 성능 모니터링 인터셉터
 * 요청 시간을 측정하고 메모리에 시계열 데이터를 저장한다.
 */
@Component
class PerformanceMonitoringInterceptor : HandlerInterceptor {
    private val requestMetrics = ConcurrentLinkedQueue<RequestMetric>()

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        request.setAttribute(REQUEST_START_TIME_MILLIS_ATTRIBUTE, System.currentTimeMillis())
        return true
    }

    /**
     * 요청 후처리: 응답 시간을 계산하고 메트릭을 저장한다.
     */
    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        val startTime = request.getAttribute(REQUEST_START_TIME_MILLIS_ATTRIBUTE) as? Long ?: return
        val endTime = System.currentTimeMillis()
        val responseTime = endTime - startTime

        val metric = RequestMetric(
            timestamp = Instant.ofEpochMilli(endTime),
            responseTime = responseTime,
            statusCode = response.status
        )

        addMetric(metric)
    }

    /**
     * 메트릭을 추가한다.
     * 최대 크기를 초과하면 오래된 메트릭을 제거한다.
     */
    private fun addMetric(metric: RequestMetric) {
        requestMetrics.offer(metric)
        if (requestMetrics.size <= MAX_METRICS_SIZE) {
            return
        }
        requestMetrics.poll()
    }

    /**
     * 최근 지정된 시간(분) 동안의 메트릭을 조회한다.
     *
     * @param minutes 조회할 시간 범위 (분)
     * @return 해당 시간 범위의 메트릭 리스트
     */
    fun getRecentMetrics(minutes: Int): List<RequestMetric> {
        val cutoffTime = Instant.now().minusSeconds(minutes * 60L)
        return requestMetrics.filter { it.timestamp.isAfter(cutoffTime) }
    }

    /**
     * 최근 지정된 시간(분) 동안의 분당 요청 수(RPM)를 계산한다.
     *
     * @param minutes 조회할 시간 범위 (분)
     * @return 분당 요청 수
     */
    fun calculateRPM(minutes: Int): Double {
        val recentMetrics = getRecentMetrics(minutes)
        if (recentMetrics.isEmpty()) {
            return 0.0
        }
        return recentMetrics.size.toDouble() / minutes
    }

    /**
     * 최근 지정된 시간(분) 동안의 평균 응답 시간을 계산한다.
     *
     * @param minutes 조회할 시간 범위 (분)
     * @return 평균 응답 시간 (밀리초)
     */
    fun calculateAverageResponseTime(minutes: Int): Double {
        val recentMetrics = getRecentMetrics(minutes)
        if (recentMetrics.isEmpty()) {
            return 0.0
        }
        return recentMetrics.map { it.responseTime }.average()
    }

    companion object {
        private const val REQUEST_START_TIME_MILLIS_ATTRIBUTE = "requestStartTimeMillis"
        private const val MAX_METRICS_SIZE = 10_000
    }
}

/**
 * 요청 메트릭 데이터
 */
data class RequestMetric(
    val timestamp: Instant,
    val responseTime: Long, // 밀리초
    val statusCode: Int
)

