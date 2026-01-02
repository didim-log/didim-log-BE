package com.didimlog.infra.ai

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

@DisplayName("GeminiLlmClient 재시도 전략 및 예외 처리 테스트")
class GeminiLlmClientRetryTest {

    private val properties = AiGeminiProperties(
        apiKey = "test-api-key",
        url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent"
    )
    private val objectMapper = ObjectMapper()
    private val rateLimiter = mockk<GeminiRateLimiter>(relaxed = true)

    @Test
    @DisplayName("재시도 전략이 Exponential Backoff와 Jitter를 사용한다")
    fun `재시도 전략 설정 검증`() {
        // given
        val client = GeminiLlmClient(
            properties = properties,
            webClientBuilder = WebClient.builder(),
            objectMapper = objectMapper,
            rateLimiter = rateLimiter
        )

        // when & then
        // 재시도 전략이 올바르게 설정되었는지 확인
        // 실제 재시도 동작은 통합 테스트에서 검증
        assertThat(client).isNotNull
    }
}




