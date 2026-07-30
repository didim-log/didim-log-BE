package com.didimlog.global.ratelimit

import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@DisplayName("Rate Limit 인터셉터 MVC 테스트")
class RateLimitInterceptorMvcTest {

    @Test
    @DisplayName("Redis 연결 실패 시 인증 로직을 실행하지 않고 503을 반환한다")
    fun `returns service unavailable when redis is down`() {
        val rateLimitService = mockk<RateLimitService>()
        every {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        } throws RateLimitUnavailableException(
            RedisConnectionFailureException("Redis unavailable")
        )
        val mockMvc = MockMvcBuilders
            .standaloneSetup(TestAuthController())
            .addInterceptors(RateLimitInterceptor(rateLimitService, jacksonObjectMapper()))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        mockMvc.perform(
            post("/api/v1/auth/login")
                .with { request ->
                    request.remoteAddr = "198.51.100.20"
                    request
                }
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("Service Unavailable"))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(true))
    }

    @RestController
    private class TestAuthController {

        @PostMapping("/api/v1/auth/login")
        fun login(): Map<String, String> {
            return mapOf("status" to "should-not-run")
        }
    }
}
