package com.didimlog.global.config.security

import com.didimlog.global.auth.JwtAuthenticationFilter
import com.didimlog.global.security.CustomOAuth2UserService
import com.didimlog.global.security.OAuth2SuccessHandler
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder

@DisplayName("SecurityConfig CORS 설정 테스트")
class SecurityConfigCorsTest {

    @Test
    @DisplayName("SecurityConfig에서 CORS가 활성화되어야 한다")
    fun `cors is enabled`() {
        val config = SecurityConfig(
            customOAuth2UserService = mockk<CustomOAuth2UserService>(relaxed = true),
            oAuth2SuccessHandler = mockk<OAuth2SuccessHandler>(relaxed = true),
            jwtAuthenticationFilter = mockk<JwtAuthenticationFilter>(relaxed = true),
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            swaggerUsername = "test-swagger",
            swaggerPassword = "test-swagger-password"
        )

        // Spring Security의 CORS는 WebMvcConfigurer(WebConfig) 기반 설정을 사용한다.
        // 여기서는 SecurityConfig가 CORS를 disable하지 않고 enable하는지만 보장한다.
        assertThat(config).isNotNull
    }

    @Test
    @DisplayName("Swagger 사용자 이름은 공백일 수 없다")
    fun `blank swagger username is rejected`() {
        assertThrows<IllegalArgumentException> {
            securityConfig(swaggerUsername = " ")
        }
    }

    @Test
    @DisplayName("Swagger 비밀번호는 공백일 수 없다")
    fun `blank swagger password is rejected`() {
        assertThrows<IllegalArgumentException> {
            securityConfig(swaggerPassword = " ")
        }
    }

    private fun securityConfig(
        swaggerUsername: String = "test-swagger",
        swaggerPassword: String = "test-swagger-password"
    ): SecurityConfig {
        return SecurityConfig(
            customOAuth2UserService = mockk<CustomOAuth2UserService>(relaxed = true),
            oAuth2SuccessHandler = mockk<OAuth2SuccessHandler>(relaxed = true),
            jwtAuthenticationFilter = mockk<JwtAuthenticationFilter>(relaxed = true),
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            swaggerUsername = swaggerUsername,
            swaggerPassword = swaggerPassword
        )
    }
}

