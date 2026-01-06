package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminDashboardService
import com.didimlog.application.admin.AdminDashboardStats
import com.didimlog.application.admin.PerformanceMetricsService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("AdminDashboardController 테스트")
@WebMvcTest(
    controllers = [AdminDashboardController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, AdminDashboardControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AdminDashboardControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminDashboardService: AdminDashboardService

    @Autowired
    private lateinit var performanceMetricsService: PerformanceMetricsService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun adminDashboardService(): AdminDashboardService = mockk(relaxed = true)

        @Bean
        fun performanceMetricsService(): PerformanceMetricsService = mockk(relaxed = true)

        @Bean
        fun adminDashboardChartService(): com.didimlog.application.admin.AdminDashboardChartService = mockk(relaxed = true)

        @Bean
        fun aiQualityService(): com.didimlog.application.admin.AiQualityService = mockk(relaxed = true)

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("대시보드 통계 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `대시보드 통계 조회 성공`() {
        // given
        val stats = AdminDashboardStats(
            totalUsers = 150L,
            todaySignups = 5L,
            totalSolvedProblems = 1250L,
            todayRetrospectives = 12L,
            aiMetrics = com.didimlog.application.admin.AiMetrics(
                averageDurationMillis = 1500L,
                totalGeneratedCount = 100L,
                timeoutCount = 2L
            )
        )

        every { adminDashboardService.getDashboardStats() } returns stats

        // when & then
        mockMvc.perform(
            get("/api/v1/admin/dashboard/stats")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalUsers").value(150))
            .andExpect(jsonPath("$.todaySignups").value(5))
            .andExpect(jsonPath("$.totalSolvedProblems").value(1250))
            .andExpect(jsonPath("$.todayRetrospectives").value(12))

        verify(exactly = 1) { adminDashboardService.getDashboardStats() }
    }

    @Test
    @DisplayName("성능 메트릭 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `성능 메트릭 조회 성공`() {
        // given
        clearMocks(performanceMetricsService)
        val metrics = PerformanceMetricsService.PerformanceMetrics(
            rpm = 45.5,
            averageResponseTime = 125.3,
            timeRangeMinutes = 30,
            rpmTimeSeries = emptyList(),
            latencyTimeSeries = emptyList()
        )

        every { performanceMetricsService.getPerformanceMetrics(30) } returns metrics

        // when & then
        mockMvc.perform(
            get("/api/v1/admin/dashboard/metrics")
                .param("minutes", "30")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rpm").value(45.5))
            .andExpect(jsonPath("$.averageResponseTime").value(125.3))
            .andExpect(jsonPath("$.timeRangeMinutes").value(30))

        verify(exactly = 1) { performanceMetricsService.getPerformanceMetrics(30) }
    }

    @Test
    @DisplayName("성능 메트릭 조회 시 기본값(30분)으로 조회")
    fun `성능 메트릭 조회 - 기본값`() {
        // given
        clearMocks(performanceMetricsService)
        val metrics = PerformanceMetricsService.PerformanceMetrics(
            rpm = 30.0,
            averageResponseTime = 100.0,
            timeRangeMinutes = 30,
            rpmTimeSeries = emptyList(),
            latencyTimeSeries = emptyList()
        )

        every { performanceMetricsService.getPerformanceMetrics(30) } returns metrics

        // when & then
        mockMvc.perform(
            get("/api/v1/admin/dashboard/metrics")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rpm").value(30.0))
            .andExpect(jsonPath("$.averageResponseTime").value(100.0))
            .andExpect(jsonPath("$.timeRangeMinutes").value(30))

        verify(exactly = 1) { performanceMetricsService.getPerformanceMetrics(30) }
    }

    @Test
    @DisplayName("성능 메트릭 조회 시 예상치 못한 예외가 발생하면 500 Internal Server Error 반환")
    fun `성능 메트릭 조회 - 500`() {
        // given
        clearMocks(performanceMetricsService)
        every { performanceMetricsService.getPerformanceMetrics(30) } throws RuntimeException("unexpected")

        // when & then
        mockMvc.perform(
            get("/api/v1/admin/dashboard/metrics")
                .param("minutes", "30")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
    }
}

