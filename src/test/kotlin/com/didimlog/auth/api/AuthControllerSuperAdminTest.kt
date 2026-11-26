package com.didimlog.auth.api

import com.didimlog.auth.api.dto.SuperAdminRequest
import com.didimlog.auth.application.AuthService
import com.didimlog.auth.application.FindAccountService
import com.didimlog.auth.application.RefreshTokenService
import com.didimlog.auth.application.boj.BojOwnershipVerificationService
import com.didimlog.auth.application.oauth.OAuthExchangeService
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.GlobalExceptionHandler
import com.didimlog.global.ratelimit.RateLimitInterceptor
import com.didimlog.student.domain.Tier
import com.didimlog.student.domain.repository.StudentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration
class SuperAdminControllerTestConfig {
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
    fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

    @Bean
    fun studentRepository(): StudentRepository = mockk(relaxed = true)

    @Bean
    fun rateLimitInterceptor(): RateLimitInterceptor = mockk(relaxed = true)
}

private fun MockMvc.postSuperAdmin(
    objectMapper: ObjectMapper,
    request: SuperAdminRequest
): ResultActions {
    return perform(
        post("/api/v1/auth/super-admin")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
    )
}

private fun superAdminRequest(adminKey: String) = SuperAdminRequest(
    bojId = "admin123",
    password = "ValidPassword123!",
    email = "admin@example.com",
    adminKey = adminKey
)

@DisplayName("AuthController 슈퍼 관리자 생성 테스트")
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [SecurityAutoConfiguration::class, OAuth2ClientAutoConfiguration::class]
)
@Import(GlobalExceptionHandler::class, SuperAdminControllerTestConfig::class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = ["app.admin.secret-key=test-secret-key"])
class AuthControllerSuperAdminTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        clearMocks(authService)
    }

    @Test
    @DisplayName("관리자 키가 일치하면 슈퍼 관리자 계정을 만들고 토큰을 반환한다")
    fun `관리자 키 일치`() {
        every {
            authService.createSuperAdmin("admin123", "ValidPassword123!", "admin@example.com", any())
        } returns AuthService.AuthResult(
            token = "admin-token",
            refreshToken = "admin-refresh-token",
            rating = 1500,
            tier = Tier.GOLD,
            tierLevel = 11
        )

        mockMvc.postSuperAdmin(objectMapper, superAdminRequest("test-secret-key"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("admin-token"))
            .andExpect(jsonPath("$.refreshToken").value("admin-refresh-token"))
            .andExpect(jsonPath("$.rating").value(1500))
            .andExpect(jsonPath("$.tier").value("GOLD"))
            .andExpect(jsonPath("$.tierLevel").value(11))
            .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))

        verify(exactly = 1) {
            authService.createSuperAdmin("admin123", "ValidPassword123!", "admin@example.com", any())
        }
    }

    @Test
    @DisplayName("관리자 키가 다르면 400을 반환하고 계정을 만들지 않는다")
    fun `관리자 키 불일치`() {
        mockMvc.postSuperAdmin(objectMapper, superAdminRequest("wrong-secret-key"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
            .andExpect(jsonPath("$.message").value("관리자 키가 일치하지 않습니다."))

        verify(exactly = 0) { authService.createSuperAdmin(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("관리자 키의 앞부분만 같아도 400을 반환한다")
    fun `관리자 키 접두사만 일치`() {
        mockMvc.postSuperAdmin(objectMapper, superAdminRequest("test-secret-key-suffix"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("관리자 키가 일치하지 않습니다."))

        verify(exactly = 0) { authService.createSuperAdmin(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("관리자 키가 비어 있으면 요청 검증에서 400을 반환한다")
    fun `관리자 키 누락`() {
        mockMvc.postSuperAdmin(objectMapper, superAdminRequest(" "))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { authService.createSuperAdmin(any(), any(), any(), any()) }
    }
}

@DisplayName("AuthController 슈퍼 관리자 생성 테스트 - 관리자 키 미설정")
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [SecurityAutoConfiguration::class, OAuth2ClientAutoConfiguration::class]
)
@Import(GlobalExceptionHandler::class, SuperAdminControllerTestConfig::class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = ["app.admin.secret-key="])
class AuthControllerSuperAdminWithoutSecretKeyTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        clearMocks(authService)
    }

    @Test
    @DisplayName("서버 관리자 키가 비어 있으면 어떤 키로도 계정을 만들 수 없다")
    fun `서버 관리자 키 미설정`() {
        mockMvc.postSuperAdmin(objectMapper, superAdminRequest("any-admin-key"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
            .andExpect(jsonPath("$.message").value("관리자 키가 일치하지 않습니다."))

        verify(exactly = 0) { authService.createSuperAdmin(any(), any(), any(), any()) }
    }
}
