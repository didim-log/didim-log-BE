package com.didimlog.ui.controller

import com.didimlog.application.log.AiReviewService
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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

    @TestConfiguration
    class TestConfig {
        @Bean
        fun aiReviewService(): AiReviewService = mockk(relaxed = true)
    }

    @Test
    @DisplayName("AI 생성 실패 시 503 + AI_GENERATION_FAILED 로 응답한다")
    fun `ai generation failed`() {
        every { aiReviewService.requestOneLineReview("log-1") } throws AiGenerationFailedException()

        mockMvc.perform(post("/api/v1/logs/log-1/ai-review"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("AI_GENERATION_FAILED"))
    }
}


