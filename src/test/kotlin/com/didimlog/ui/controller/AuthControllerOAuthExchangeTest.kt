package com.didimlog.ui.controller

import com.didimlog.application.auth.AuthService
import com.didimlog.application.auth.FindAccountService
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.application.auth.oauth.OAuthExchangeService
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Tier
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.GlobalExceptionHandler
import com.didimlog.global.ratelimit.RateLimitInterceptor
import com.didimlog.global.ratelimit.RateLimitService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("AuthController OAuth 코드 교환 테스트")
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, AuthControllerOAuthExchangeTest.TestConfig::class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = ["app.admin.secret-key=test-secret-key"])
class AuthControllerOAuthExchangeTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var oAuthExchangeService: OAuthExchangeService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun authService(): AuthService = mockk(relaxed = true)

        @Bean
        fun findAccountService(): FindAccountService = mockk(relaxed = true)

        @Bean
        fun bojOwnershipVerificationService(): BojOwnershipVerificationService = mockk(relaxed = true)

        @Bean
        fun refreshTokenService(): RefreshTokenService = mockk(relaxed = true)

        @Bean
        fun oAuthExchangeService(): OAuthExchangeService = mockk(relaxed = true)

        @Bean
        fun rateLimitService(): RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("일회용 코드를 토큰과 provider로 교환하고 캐시를 금지한다")
    fun `OAuth 코드 교환 성공`() {
        clearMocks(oAuthExchangeService)
        val code = "single-use-code"
        every { oAuthExchangeService.exchange(code) } returns OAuthExchangeService.ExchangeResult(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            rating = 1_500,
            tier = Tier.GOLD,
            tierLevel = 13,
            provider = Provider.GITHUB
        )

        mockMvc.perform(
            post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("code" to code)))
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(jsonPath("$.token").value("access-token"))
            .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
            .andExpect(jsonPath("$.rating").value(1_500))
            .andExpect(jsonPath("$.tier").value("GOLD"))
            .andExpect(jsonPath("$.tierLevel").value(13))
            .andExpect(jsonPath("$.provider").value("github"))
            .andExpect(jsonPath("$.code").doesNotExist())

        verify(exactly = 1) { oAuthExchangeService.exchange(code) }
    }

    @Test
    @DisplayName("빈 교환 코드는 400으로 거절하고 서비스를 호출하지 않는다")
    fun `OAuth 빈 코드 검증 실패`() {
        clearMocks(oAuthExchangeService)

        mockMvc.perform(
            post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"   "}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(ErrorCode.COMMON_VALIDATION_FAILED.code))

        verify(exactly = 0) { oAuthExchangeService.exchange(any()) }
    }

    @Test
    @DisplayName("만료되거나 재사용된 교환 코드는 동일한 400 오류를 반환한다")
    fun `OAuth 유효하지 않은 코드 교환 실패`() {
        clearMocks(oAuthExchangeService)
        val code = "expired-or-replayed-code"
        every { oAuthExchangeService.exchange(code) } throws BusinessException(
            ErrorCode.OAUTH_EXCHANGE_CODE_INVALID
        )

        mockMvc.perform(
            post("/api/v1/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("code" to code)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID.code))
            .andExpect(jsonPath("$.message").value(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID.message))

        verify(exactly = 1) { oAuthExchangeService.exchange(code) }
    }
}
