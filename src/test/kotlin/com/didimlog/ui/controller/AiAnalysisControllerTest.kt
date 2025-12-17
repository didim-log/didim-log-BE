package com.didimlog.ui.controller

import com.didimlog.application.ai.AiAnalysisService
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("AiAnalysisController 테스트")
@WebMvcTest(
    controllers = [AiAnalysisController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AiAnalysisControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var aiAnalysisService: AiAnalysisService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun aiAnalysisService(): AiAnalysisService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)
    }

    @Test
    @DisplayName("AI 회고록 생성 요청 시 마크다운을 반환한다")
    fun `analyze returns markdown`() {
        every { aiAnalysisService.analyze(any(), any(), any()) } returns "markdown"

        val body = mapOf(
            "code" to "print(1)",
            "problemId" to "1000",
            "isSuccess" to true
        )

        mockMvc.perform(
            post("/api/v1/ai/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.markdown").value("markdown"))
    }

    @Test
    @DisplayName("429 에러가 지속적으로 발생하면 429 Too Many Requests를 반환한다")
    fun `429 지속 발생 시 최종 응답 검증`() {
        // given
        // GeminiLlmClient에서 RetryExhaustedException이 발생하면 BusinessException(AI_SERVICE_BUSY)로 변환됨
        every { aiAnalysisService.analyze(any(), any(), any()) } throws BusinessException(
            ErrorCode.AI_SERVICE_BUSY,
            "서버 사용량이 많아 잠시 후 다시 시도해주세요."
        )

        val body = mapOf(
            "code" to "print(1)",
            "problemId" to "1000",
            "isSuccess" to true
        )

        // when & then
        mockMvc.perform(
            post("/api/v1/ai/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isTooManyRequests) // BusinessException은 ErrorCode.status(429)를 사용
            .andExpect(jsonPath("$.code").value("AI_SERVICE_BUSY"))
            .andExpect(jsonPath("$.message").value("서버 사용량이 많아 잠시 후 다시 시도해주세요."))
    }
}

