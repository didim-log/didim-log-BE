package com.didimlog.ui.controller

import com.didimlog.application.auth.AuthService
import com.didimlog.application.auth.FindAccountService
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.application.auth.oauth.OAuthExchangeService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.didimlog.ui.dto.RefreshTokenRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor

@DisplayName("AuthController Refresh Token 테스트")
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, AuthControllerRefreshTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = ["app.admin.secret-key=test-secret-key"])
class AuthControllerRefreshTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var refreshTokenService: RefreshTokenService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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
        fun methodValidationPostProcessor(): MethodValidationPostProcessor {
            return MethodValidationPostProcessor()
        }

        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true) {
            every { isAllowed(any(), any(), any()) } returns true
        }

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("유효한 Refresh Token으로 토큰 갱신 시 200 OK 및 새 토큰 반환")
    fun `토큰 갱신 성공`() {
        // given
        clearMocks(refreshTokenService)
        val refreshToken = "valid-refresh-token"
        val newAccessToken = "new-access-token"
        val newRefreshToken = "new-refresh-token"
        val result = RefreshTokenService.RefreshResult(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            rating = 30,
            tier = com.didimlog.domain.enums.Tier.BRONZE,
            tierLevel = 3
        )

        every { refreshTokenService.refresh(refreshToken) } returns result

        val requestBody = RefreshTokenRequest(refreshToken) // refreshToken은 이제 nullable이지만 테스트에서는 non-null 값 사용

        // when & then
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value(newAccessToken))
            .andExpect(jsonPath("$.refreshToken").value(newRefreshToken))
            .andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
            .andExpect(jsonPath("$.rating").value(30))
            .andExpect(jsonPath("$.tier").value("BRONZE"))
            .andExpect(jsonPath("$.tierLevel").value(3))

        verify(exactly = 1) { refreshTokenService.refresh(refreshToken) }
    }

    @Test
    @DisplayName("refreshToken이 없으면 400 Bad Request")
    fun `refreshToken 필수 검증`() {
        // given
        // when & then
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("유효하지 않은 Refresh Token으로 갱신 시도 시 400 Bad Request")
    fun `유효하지 않은 Refresh Token으로 갱신 실패`() {
        // given
        val refreshToken = "invalid-refresh-token"

        every { refreshTokenService.refresh(refreshToken) } throws
            com.didimlog.global.exception.BusinessException(
                com.didimlog.global.exception.ErrorCode.COMMON_INVALID_INPUT,
                "유효하지 않은 Refresh Token입니다."
            )

        val requestBody = RefreshTokenRequest(refreshToken) // refreshToken은 이제 nullable이지만 테스트에서는 non-null 값 사용

        // when & then
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("유효하지 않은 Refresh Token입니다."))

        verify(exactly = 1) { refreshTokenService.refresh(refreshToken) }
    }
}
