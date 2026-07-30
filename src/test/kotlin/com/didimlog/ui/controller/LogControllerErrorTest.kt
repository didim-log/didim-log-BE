package com.didimlog.ui.controller

import com.didimlog.application.log.AiReviewService
import com.didimlog.application.log.LogService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@DisplayName("LogController 에러 응답 테스트")
@WebMvcTest(
    controllers = [LogController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, LogControllerErrorTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class LogControllerErrorTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var aiReviewService: AiReviewService

    @Autowired
    private lateinit var logService: LogService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @TestConfiguration
    class TestConfig {
        @Bean
        fun aiReviewService(): AiReviewService = mockk(relaxed = true)

        @Bean
        fun logService(): com.didimlog.application.log.LogService = mockk(relaxed = true)

        @Bean
        fun aiUsageService(): com.didimlog.application.ai.AiUsageService = mockk(relaxed = true)

        @Bean
        fun studentRepository(): com.didimlog.domain.repository.StudentRepository = mockk(relaxed = true)

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("AI 생성 실패 시 503 + AI_GENERATION_FAILED 로 응답한다")
    fun `ai generation failed`() {
        every {
            studentRepository.findById("student-id")
        } returns Optional.of(
            Student(
                id = "student-id",
                nickname = Nickname("user_nick"),
                provider = Provider.BOJ,
                providerId = "user123",
                bojId = BojId("user123"),
                currentTier = Tier.BRONZE,
                role = Role.USER
            )
        )
        every { aiReviewService.requestOneLineReviewAsync("log-1", "student-id") } throws AiGenerationFailedException()

        mockMvc.perform(
            post("/api/v1/logs/log-1/ai-review")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("student-id", null))
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("AI_GENERATION_FAILED"))
    }

    @Test
    @DisplayName("AI 리뷰 피드백 제출은 인증 정보가 없으면 401을 반환한다")
    fun `피드백 제출 인증 없음`() {
        mockMvc.perform(
            post("/api/v1/logs/log-1/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"LIKE"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        verify(exactly = 0) {
            logService.updateFeedback("log-1", any(), AiFeedbackStatus.LIKE, null)
        }
    }
}
