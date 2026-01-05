package com.didimlog.ui.controller

import com.didimlog.application.ranking.RankingService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.RankingPeriod
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
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

@DisplayName("RankingController 테스트")
@WebMvcTest(
    controllers = [RankingController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class RankingControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var rankingService: RankingService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun rankingService(): RankingService = mockk(relaxed = true)

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
    @DisplayName("랭킹 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `랭킹 조회 성공`() {
        // given
        val students = listOf(
            createStudent("student1", "user1"),
            createStudent("student2", "user2")
        )
        val rankers = listOf(
            com.didimlog.application.ranking.RankingInfo(rank = 1, student = students[0], retrospectiveCount = 10L),
            com.didimlog.application.ranking.RankingInfo(rank = 2, student = students[1], retrospectiveCount = 8L)
        )

        every { rankingService.getTopRankers(100, RankingPeriod.TOTAL) } returns rankers

        // when & then
        mockMvc.perform(
            get("/api/v1/ranks")
                .param("limit", "100")
                .param("period", "TOTAL")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].rank").value(1))
            .andExpect(jsonPath("$[0].retrospectiveCount").value(10))
            .andExpect(jsonPath("$[0].nickname").value("user1"))
            .andExpect(jsonPath("$[0].tier").exists())
            .andExpect(jsonPath("$[0].rating").exists())
            .andExpect(jsonPath("$[1].rank").value(2))
            .andExpect(jsonPath("$[1].retrospectiveCount").value(8))
    }

    @Test
    @DisplayName("랭킹 조회 시 limit이 0 이하일 때 400 Bad Request 반환")
    fun `랭킹 조회 시 limit 유효성 검증`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/ranks")
                .param("limit", "0")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }

    private fun createStudent(id: String, nickname: String): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = nickname,
            bojId = BojId(nickname),
            password = "encoded",
            rating = 100,
            currentTier = Tier.fromRating(100),
            role = Role.USER
        )
    }
}
