package com.didimlog.ui.controller

import com.didimlog.application.ranking.RankingService
import com.didimlog.application.ranking.RankingInfo
import com.didimlog.domain.enums.RankingPeriod
import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor
import com.didimlog.global.exception.GlobalExceptionHandler
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.didimlog.global.auth.JwtTokenProvider

@DisplayName("RankingController 테스트")
@WebMvcTest(
    controllers = [RankingController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
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
        fun methodValidationPostProcessor(): MethodValidationPostProcessor {
            return MethodValidationPostProcessor()
        }
    }

    @Test
    @DisplayName("랭킹 조회 시 JSON 리스트가 정상적으로 반환된다")
    fun `랭킹 조회 성공`() {
        // given
        val students = listOf(
            Student(
                id = "student1",
                nickname = Nickname("user1"),
                provider = Provider.BOJ,
                providerId = "user1",
                bojId = BojId("user1"),
                password = "encoded",
                rating = 1000,
                currentTier = Tier.fromRating(1000),
                role = Role.USER,
                solutions = Solutions()
            ),
            Student(
                id = "student2",
                nickname = Nickname("user2"),
                provider = Provider.BOJ,
                providerId = "user2",
                bojId = BojId("user2"),
                password = "encoded",
                rating = 500,
                currentTier = Tier.fromRating(500),
                role = Role.USER,
                solutions = Solutions()
            )
        )
        val rankers = listOf(
            RankingInfo(rank = 1, student = students[0], retrospectiveCount = 3),
            RankingInfo(rank = 2, student = students[1], retrospectiveCount = 1)
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
            .andExpect(jsonPath("$[0].rank").value(1))
            .andExpect(jsonPath("$[0].nickname").value("user1"))
            .andExpect(jsonPath("$[0].tier").value("GOLD"))
            .andExpect(jsonPath("$[0].rating").value(1000))
            .andExpect(jsonPath("$[0].retrospectiveCount").value(3))
            .andExpect(jsonPath("$[1].rank").value(2))
            .andExpect(jsonPath("$[1].nickname").value("user2"))
            .andExpect(jsonPath("$[1].tier").value("SILVER"))
            .andExpect(jsonPath("$[1].rating").value(500))
            .andExpect(jsonPath("$[1].retrospectiveCount").value(1))
    }

    @Test
    @DisplayName("limit 파라미터가 없으면 기본값 100으로 조회한다")
    fun `랭킹 조회 기본값 사용`() {
        // given
        every { rankingService.getTopRankers(100, RankingPeriod.TOTAL) } returns emptyList()

        // when & then
        mockMvc.perform(
            get("/api/v1/ranks")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("limit이 0이면 400 Bad Request를 반환한다")
    fun `랭킹 조회 유효성 검증 실패 - limit 0`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/ranks")
                .param("limit", "0")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }
}


