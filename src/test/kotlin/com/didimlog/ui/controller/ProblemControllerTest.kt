package com.didimlog.ui.controller

import com.didimlog.application.problem.ProblemService
import com.didimlog.application.recommendation.CategoryFilterMode
import com.didimlog.application.recommendation.RecommendationProblemMatch
import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
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

@DisplayName("ProblemController 테스트")
@WebMvcTest(
    controllers = [ProblemController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class ProblemControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var recommendationService: RecommendationService

    @Autowired
    private lateinit var problemService: ProblemService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun recommendationService(): RecommendationService = mockk(relaxed = true)

        @Bean
        fun problemService(): ProblemService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("문제 상세 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `문제 상세 조회 성공`() {
        // given
        val problemId = 1000L
        val problem = createProblem(problemId.toString(), "A+B")

        every { problemService.getProblemDetail(problemId) } returns problem

        // when & then
        mockMvc.perform(
            get("/api/v1/problems/$problemId")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(problemId.toString()))
            .andExpect(jsonPath("$.title").value("A+B"))
            .andExpect(jsonPath("$.category").exists())
            .andExpect(jsonPath("$.primaryCategory").value("IMPLEMENTATION"))
            .andExpect(jsonPath("$.secondaryCategories").isArray)
            .andExpect(jsonPath("$.normalizedTags").isArray)
            .andExpect(jsonPath("$.difficulty").exists())
            .andExpect(jsonPath("$.url").exists())
    }

    @Test
    @DisplayName("문제 추천 시 200 OK 및 Response JSON 구조 검증")
    fun `문제 추천 성공`() {
        // given
        val recommendation = listOf(
            RecommendationProblemMatch(
                problem = createProblem("1000", "A+B"),
                matchedByPrimary = true,
                matchedByTags = true,
                expandedFrom = listOf("Breadth-first Search", "Depth-first Search")
            ),
            RecommendationProblemMatch(
                problem = createProblem("1001", "A-B"),
                matchedByPrimary = true,
                matchedByTags = false,
                expandedFrom = listOf("Breadth-first Search")
            )
        )

        every {
            recommendationService.recommendProblemsDetailed(
                "bojId",
                10,
                null,
                null,
                CategoryFilterMode.RELATED
            )
        } returns recommendation

        // when & then
        mockMvc.perform(
            get("/api/v1/problems/recommend")
                .param("count", "10")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].id").value("1000"))
            .andExpect(jsonPath("$[0].title").value("A+B"))
            .andExpect(jsonPath("$[0].primaryCategory").value("IMPLEMENTATION"))
            .andExpect(jsonPath("$[0].normalizedTags[0]").value("IMPLEMENTATION"))
            .andExpect(jsonPath("$[0].expandedFrom").isArray)
            .andExpect(jsonPath("$[1].id").value("1001"))
            .andExpect(jsonPath("$[1].title").value("A-B"))
    }

    @Test
    @DisplayName("문제 추천 시 count가 1 미만일 때 400 Bad Request 반환")
    fun `문제 추천 시 count 최소값 유효성 검증`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/problems/recommend")
                .param("count", "0")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("문제 추천 시 count가 50 초과일 때 400 Bad Request 반환")
    fun `문제 추천 시 count 최대값 유효성 검증`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/problems/recommend")
                .param("count", "51")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("카테고리 메타 조회 시 200 OK 반환")
    fun `카테고리 메타 조회 성공`() {
        mockMvc.perform(
            get("/api/v1/problems/categories/meta")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    @DisplayName("문제 상세 조회 시 problemId가 0 이하일 때 400 Bad Request 반환")
    fun `문제 상세 조회 시 problemId 유효성 검증`() {
        // when & then
        mockMvc.perform(
            get("/api/v1/problems/0")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }

    private fun createProblem(id: String, title: String): Problem {
        return Problem(
            id = ProblemId(id),
            title = title,
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$id"
        )
    }
}

