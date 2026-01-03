package com.didimlog.global.config.security

import com.didimlog.global.auth.JwtAuthenticationFilter
import com.didimlog.global.security.CustomOAuth2UserService
import com.didimlog.global.security.OAuth2SuccessHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security 설정 클래스
 * OAuth2 소셜 로그인과 JWT 인증을 지원한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customOAuth2UserService: CustomOAuth2UserService,
    private val oAuth2SuccessHandler: OAuth2SuccessHandler,
    private val jwtAuthenticationFilter: JwtAuthenticationFilter
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/system/**",
                        "/api/v1/notices/**", // 공지사항 조회는 인증 없이 접근 가능 (점검 공지 조회용)
                        "/login/oauth2/**",
                        "/oauth2/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/error"
                    ).permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .userInfoEndpoint { userInfo ->
                        userInfo.userService(customOAuth2UserService)
                    }
                    .successHandler(oAuth2SuccessHandler)
            }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler())
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    /**
     * 인증 실패 시 처리 (401 Unauthorized)
     */
    @Bean
    fun authenticationEntryPoint(): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                """
                {
                    "status": 401,
                    "error": "Unauthorized",
                    "code": "UNAUTHORIZED",
                    "message": "인증이 필요합니다. JWT 토큰을 확인해주세요."
                }
                """.trimIndent()
            )
        }
    }

    /**
     * 권한 부족 시 처리 (403 Forbidden)
     */
    @Bean
    fun accessDeniedHandler(): AccessDeniedHandler {
        return AccessDeniedHandler { _, response, _ ->
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                """
                {
                    "status": 403,
                    "error": "Forbidden",
                    "code": "ACCESS_DENIED",
                    "message": "접근 권한이 없습니다."
                }
                """.trimIndent()
            )
        }
    }
}
