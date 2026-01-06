package com.didimlog.global.config.security

import com.didimlog.global.auth.JwtAuthenticationFilter
import com.didimlog.global.security.CustomOAuth2UserService
import com.didimlog.global.security.OAuth2SuccessHandler
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder

@DisplayName("SecurityConfig CORS 설정 테스트")
class SecurityConfigCorsTest {

    @Test
    @DisplayName("allowed-origins에 쉼표로 구분된 Origin을 지정하면 해당 Origin만 허용한다")
    fun `cors allows configured origins only`() {
        val config = securityConfig(allowedOrigins = "http://localhost:5173,http://localhost:3000")
        val corsConfiguration = config.corsConfigurationSource().getCorsConfiguration(request("/api/v1/health"))

        requireNotNull(corsConfiguration)
        assertThat(corsConfiguration.allowedOrigins)
            .containsExactlyInAnyOrder("http://localhost:5173", "http://localhost:3000")
        assertThat(corsConfiguration.allowedOriginPatterns).isNullOrEmpty()
        assertThat(corsConfiguration.allowCredentials).isTrue()
    }

    @Test
    @DisplayName("allowed-origins에 *이 포함되면 모든 Origin 패턴을 허용한다")
    fun `cors allows all origins when wildcard configured`() {
        val config = securityConfig(allowedOrigins = "*")
        val corsConfiguration = config.corsConfigurationSource().getCorsConfiguration(request("/api/v1/health"))

        requireNotNull(corsConfiguration)
        assertThat(corsConfiguration.allowedOriginPatterns).containsExactly("*")
    }

    private fun securityConfig(allowedOrigins: String): SecurityConfig {
        return SecurityConfig(
            customOAuth2UserService = mockk<CustomOAuth2UserService>(relaxed = true),
            oAuth2SuccessHandler = mockk<OAuth2SuccessHandler>(relaxed = true),
            jwtAuthenticationFilter = mockk<JwtAuthenticationFilter>(relaxed = true),
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            allowedOrigins = allowedOrigins,
            swaggerUsername = "admin",
            swaggerPassword = "admin123"
        )
    }

    private fun request(uri: String): HttpServletRequest {
        return MockHttpServletRequest("GET", uri)
    }
}


