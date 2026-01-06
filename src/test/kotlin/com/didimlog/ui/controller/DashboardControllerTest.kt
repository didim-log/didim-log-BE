package com.didimlog.ui.controller

import com.didimlog.application.dashboard.DashboardInfo
import com.didimlog.application.dashboard.DashboardService
import com.didimlog.domain.enums.Tier
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

@DisplayName("DashboardController 테스트")
@WebMvcTest(
    controllers = [DashboardController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dashboardService: DashboardService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun dashboardService(): DashboardService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        @Bean
        fun methodValidationPostProcessor(): org.springframework.validation.beanvalidation.MethodValidationPostProcessor {
            return org.springframework.validation.beanvalidation.MethodValidationPostProcessor()
        }

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("대시보드 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `대시보드 조회 성공`() {
        // given
        val dashboardInfo = DashboardInfo(
            studentProfile = com.didimlog.application.dashboard.StudentProfile(
                nickname = "user1",
                bojId = "bojId",
                currentTier = Tier.BRONZE,
                solvedAcTierLevel = 1,
                consecutiveSolveDays = 5
            ),
            todaySolvedCount = 1,
            todaySolvedProblems = listOf(
                com.didimlog.application.dashboard.TodaySolvedProblem(
                    problemId = "1000",
                    result = "SUCCESS",
                    solvedAt = java.time.LocalDateTime.now()
                )
            ),
            quote = com.didimlog.application.dashboard.QuoteInfo(id = "quote1", content = "명언", author = "작가"),
            currentTierTitle = "BRONZE",
            nextTierTitle = "SILVER",
            currentRating = 100,
            requiredRatingForNextTier = 200,
            progressPercentage = 50
        )

        every { dashboardService.getDashboard("bojId") } returns dashboardInfo

        // when & then
        mockMvc.perform(
            get("/api/v1/dashboard")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.studentProfile.nickname").value("user1"))
            .andExpect(jsonPath("$.studentProfile.bojId").value("bojId"))
            .andExpect(jsonPath("$.studentProfile.currentTier").value("BRONZE"))
            .andExpect(jsonPath("$.studentProfile.consecutiveSolveDays").value(5))
            .andExpect(jsonPath("$.todaySolvedCount").value(1))
            .andExpect(jsonPath("$.todaySolvedProblems").isArray)
            .andExpect(jsonPath("$.quote.id").value("quote1"))
            .andExpect(jsonPath("$.quote.content").value("명언"))
            .andExpect(jsonPath("$.quote.author").value("작가"))
            .andExpect(jsonPath("$.currentTierTitle").value("BRONZE"))
            .andExpect(jsonPath("$.nextTierTitle").value("SILVER"))
            .andExpect(jsonPath("$.currentRating").value(100))
            .andExpect(jsonPath("$.requiredRatingForNextTier").value(200))
            .andExpect(jsonPath("$.progressPercentage").value(50))
    }
}

