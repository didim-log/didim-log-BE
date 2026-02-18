package com.didimlog.application.admin

import com.didimlog.global.interceptor.PerformanceMonitoringInterceptor
import com.didimlog.global.interceptor.RequestMetric
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("PerformanceMetricsService 테스트")
class PerformanceMetricsServiceTest {

    private val monitoringInterceptor = mockk<PerformanceMonitoringInterceptor>()
    private val service = PerformanceMetricsService(monitoringInterceptor)

    @Test
    @DisplayName("최근 요청이 없으면 기본값 메트릭을 반환한다")
    fun `최근 요청이 없으면 기본값 메트릭 반환`() {
        every { monitoringInterceptor.getRecentMetrics(30) } returns emptyList()

        val metrics = service.getPerformanceMetrics(30)

        assertThat(metrics.rpm).isEqualTo(0.0)
        assertThat(metrics.averageResponseTime).isEqualTo(0.0)
        assertThat(metrics.p95ResponseTime).isEqualTo(0.0)
        assertThat(metrics.totalRequests).isEqualTo(0)
        assertThat(metrics.errorRate).isEqualTo(0.0)
        assertThat(metrics.rpmTimeSeries).hasSize(30)
        assertThat(metrics.latencyTimeSeries).hasSize(30)
        assertThat(metrics.errorRateTimeSeries).hasSize(30)
    }

    @Test
    @DisplayName("최근 요청 기반으로 운영 지표를 계산한다")
    fun `최근 요청 기반으로 운영 지표 계산`() {
        val now = Instant.now()
        val recentMetrics = listOf(
            RequestMetric(timestamp = now.minusSeconds(10), responseTime = 120, statusCode = 200),
            RequestMetric(timestamp = now.minusSeconds(20), responseTime = 450, statusCode = 500),
            RequestMetric(timestamp = now.minusSeconds(70), responseTime = 80, statusCode = 404),
            RequestMetric(timestamp = now.minusSeconds(100), responseTime = 1500, statusCode = 200)
        )
        every { monitoringInterceptor.getRecentMetrics(2) } returns recentMetrics

        val metrics = service.getPerformanceMetrics(2)

        assertThat(metrics.totalRequests).isEqualTo(4)
        assertThat(metrics.rpm).isEqualTo(2.0)
        assertThat(metrics.averageResponseTime).isEqualTo(537.5)
        assertThat(metrics.p95ResponseTime).isEqualTo(1500.0)
        assertThat(metrics.maxResponseTime).isEqualTo(1500.0)
        assertThat(metrics.errorRequests).isEqualTo(2)
        assertThat(metrics.serverErrorRequests).isEqualTo(1)
        assertThat(metrics.errorRate).isEqualTo(50.0)
        assertThat(metrics.serverErrorRate).isEqualTo(25.0)
        assertThat(metrics.slowRequestRate).isEqualTo(25.0)

        val summaryByCode = metrics.statusCodeSummary.associateBy { it.statusCode }
        assertThat(summaryByCode[200]?.count).isEqualTo(2)
        assertThat(summaryByCode[500]?.count).isEqualTo(1)
        assertThat(summaryByCode[404]?.count).isEqualTo(1)
        assertThat(metrics.rpmTimeSeries).hasSize(2)
        assertThat(metrics.latencyTimeSeries).hasSize(2)
        assertThat(metrics.errorRateTimeSeries).hasSize(2)
    }
}
