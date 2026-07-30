package com.didimlog.infra.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.ratelimit.RateLimitUnavailableException
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

@DisplayName("GeminiLlmClient 재시도 허가 테스트")
class GeminiLlmClientRetryTest {

    private val objectMapper = ObjectMapper()

    @Test
    @DisplayName("429 재시도마다 호출 허가를 다시 받고 성공 응답을 반환한다")
    fun `retry obtains permission before every http request`() {
        val server = startServer { attempt ->
            if (attempt == 1) {
                429 to """{"error":"rate limited"}"""
            } else {
                200 to successResponse("review-ok")
            }
        }
        val rateLimiter = mockk<GeminiRateLimiter>()
        every { rateLimiter.checkAndIncrement() } returns Unit
        val client = createClient(server, rateLimiter)

        try {
            val result = client.generateMarkdown("system", "user")

            assertThat(result).isEqualTo("review-ok")
            assertThat(server.requestCount.get()).isEqualTo(2)
            verify(exactly = 2) { rateLimiter.checkAndIncrement() }
        } finally {
            server.server.disposeNow()
        }
    }

    @Test
    @DisplayName("재시도 허가가 거절되면 두 번째 HTTP 요청을 보내지 않는다")
    fun `retry permission rejection stops before second request`() {
        val server = startServer {
            429 to """{"error":"rate limited"}"""
        }
        val rateLimiter = mockk<GeminiRateLimiter>()
        val permitCalls = AtomicInteger()
        every { rateLimiter.checkAndIncrement() } answers {
            if (permitCalls.incrementAndGet() > 1) {
                throw BusinessException(ErrorCode.AI_SERVICE_BUSY)
            }
        }
        val client = createClient(server, rateLimiter)

        try {
            assertThatThrownBy {
                client.generateMarkdown("system", "user")
            }
                .isInstanceOf(BusinessException::class.java)
                .matches {
                    (it as BusinessException).errorCode == ErrorCode.AI_SERVICE_BUSY
                }

            assertThat(server.requestCount.get()).isEqualTo(1)
            verify(exactly = 2) { rateLimiter.checkAndIncrement() }
        } finally {
            server.server.disposeNow()
        }
    }

    @Test
    @DisplayName("호출 제한 저장소 장애가 나면 HTTP 요청 전에 503 예외를 유지한다")
    fun `rate limiter outage stops before first http request`() {
        val server = startServer {
            200 to successResponse("unused")
        }
        val rateLimiter = mockk<GeminiRateLimiter>()
        val connectionFailure =
            RedisConnectionFailureException("Redis unavailable")
        every {
            rateLimiter.checkAndIncrement()
        } throws RateLimitUnavailableException(connectionFailure)
        val client = createClient(server, rateLimiter)

        try {
            assertThatThrownBy {
                client.generateMarkdown("system", "user")
            }
                .isInstanceOf(RateLimitUnavailableException::class.java)
                .hasCause(connectionFailure)

            assertThat(server.requestCount.get()).isZero()
            verify(exactly = 1) { rateLimiter.checkAndIncrement() }
        } finally {
            server.server.disposeNow()
        }
    }

    @Test
    @DisplayName("429 재시도 한도를 모두 쓰면 호출 허가 횟수를 유지하고 혼잡 예외를 반환한다")
    fun `retry exhaustion preserves busy response and permit count`() {
        val server = startServer {
            429 to """{"error":"rate limited"}"""
        }
        val rateLimiter = mockk<GeminiRateLimiter>()
        every { rateLimiter.checkAndIncrement() } returns Unit
        val client = createClient(server, rateLimiter)

        try {
            assertThatThrownBy {
                client.generateMarkdown("system", "user")
            }
                .isInstanceOf(BusinessException::class.java)
                .matches {
                    (it as BusinessException).errorCode == ErrorCode.AI_SERVICE_BUSY
                }

            assertThat(server.requestCount.get()).isEqualTo(2)
            verify(exactly = 2) { rateLimiter.checkAndIncrement() }
        } finally {
            server.server.disposeNow()
        }
    }

    @Test
    @DisplayName("첫 429 뒤 호출 제한 저장소가 실패하면 추가 HTTP 없이 503을 유지한다")
    fun `rate limiter outage after 429 stops retry before next http request`() {
        val server = startServer {
            429 to """{"error":"rate limited"}"""
        }
        val rateLimiter = mockk<GeminiRateLimiter>()
        val permitCalls = AtomicInteger()
        val connectionFailure =
            RedisConnectionFailureException("Redis unavailable")
        every { rateLimiter.checkAndIncrement() } answers {
            if (permitCalls.incrementAndGet() > 1) {
                throw RateLimitUnavailableException(connectionFailure)
            }
        }
        val client = createClient(server, rateLimiter)

        try {
            assertThatThrownBy {
                client.generateMarkdown("system", "user")
            }
                .isInstanceOf(RateLimitUnavailableException::class.java)
                .hasCause(connectionFailure)

            assertThat(server.requestCount.get()).isEqualTo(1)
            verify(exactly = 2) { rateLimiter.checkAndIncrement() }
        } finally {
            server.server.disposeNow()
        }
    }

    private fun createClient(
        server: TestServer,
        rateLimiter: GeminiRateLimiter
    ): GeminiLlmClient {
        return GeminiLlmClient(
            properties = AiGeminiProperties(
                apiKey = "test-api-key",
                url = "http://127.0.0.1:${server.server.port()}/generate",
                maxRetries = 1,
                retryBackoffMillis = 1,
                rateLimit = GeminiRateLimitProperties(
                    minIntervalSeconds = 0
                )
            ),
            webClientBuilder = WebClient.builder(),
            objectMapper = objectMapper,
            rateLimiter = rateLimiter
        )
    }

    private fun startServer(
        response: (attempt: Int) -> Pair<Int, String>
    ): TestServer {
        val requestCount = AtomicInteger()
        val server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle { _, httpResponse ->
                val attempt = requestCount.incrementAndGet()
                val (status, body) = response(attempt)
                httpResponse.status(status)
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just(body))
            }
            .bindNow()
        return TestServer(server, requestCount)
    }

    private fun successResponse(text: String): String {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "$text"}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private data class TestServer(
        val server: DisposableServer,
        val requestCount: AtomicInteger
    )
}
