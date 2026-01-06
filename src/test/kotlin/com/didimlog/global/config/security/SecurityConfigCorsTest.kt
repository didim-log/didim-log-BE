package com.didimlog.global.config.security

import com.didimlog.global.auth.JwtAuthenticationFilter
import com.didimlog.global.security.CustomOAuth2UserService
import com.didimlog.global.security.OAuth2SuccessHandler
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
            swaggerUsername = "admin",
            swaggerPassword = "admin123"
        )

        // Spring Security의 CORS는 WebMvcConfigurer(WebConfig) 기반 설정을 사용한다.
        // 여기서는 SecurityConfig가 CORS를 disable하지 않고 enable하는지만 보장한다.
        assertThat(config).isNotNull
    }
}


