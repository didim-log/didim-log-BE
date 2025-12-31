package com.didimlog.ui.controller

import com.didimlog.application.statistics.StatisticsService
import com.didimlog.application.statistics.StatisticsInfo
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
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

@DisplayName("StatisticsController 테스트")
@WebMvcTest(
    controllers = [StatisticsController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class StatisticsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var statisticsService: StatisticsService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun statisticsService(): StatisticsService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        @Bean
        fun methodValidationPostProcessor(): org.springframework.validation.beanvalidation.MethodValidationPostProcessor {
            return org.springframework.validation.beanvalidation.MethodValidationPostProcessor()
        }
    }

    @Test
    @DisplayName("통계 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `통계 조회 성공`() {
        // given
        val statisticsInfo = StatisticsInfo(
            monthlyHeatmap = emptyList(),
            categoryDistribution = emptyMap(),
            algorithmCategoryDistribution = emptyMap(),
            topUsedAlgorithms = emptyList(),
            totalSolvedCount = 10,
            totalRetrospectives = 0L,
            averageSolveTime = 0.0,
            successRate = 0.0,
            tagRadarData = emptyList()
        )

        every { statisticsService.getStatistics("bojId") } returns statisticsInfo

        // when & then
        mockMvc.perform(
            get("/api/v1/statistics")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.monthlyHeatmap").exists())
            .andExpect(jsonPath("$.categoryDistribution").exists())
            .andExpect(jsonPath("$.algorithmCategoryDistribution").exists())
            .andExpect(jsonPath("$.topUsedAlgorithms").exists())
            .andExpect(jsonPath("$.totalSolvedCount").value(10))
    }
}


