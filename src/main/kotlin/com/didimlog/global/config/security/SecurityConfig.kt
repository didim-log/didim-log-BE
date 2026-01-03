package com.didimlog.global.config.security

import com.didimlog.global.auth.JwtAuthenticationFilter
import com.didimlog.global.security.CustomOAuth2UserService
import com.didimlog.global.security.OAuth2SuccessHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint
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
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    @Value("\${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private val allowedOrigins: String,
    @Value("\${spring.security.user.name:admin}")
    private val swaggerUsername: String,
    @Value("\${spring.security.user.password:admin123}")
    private val swaggerPassword: String
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
                        "/error"
                    ).permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").authenticated()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .httpBasic { it.authenticationEntryPoint(basicAuthenticationEntryPoint()) }
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

    /**
     * PasswordEncoder Bean
     * Swagger UI 인증에 사용
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    /**
     * Swagger UI 접근을 위한 UserDetailsService
     * application.yaml의 평문 비밀번호를 BCrypt로 인코딩하여 사용
     */
    @Bean
    fun swaggerUserDetailsService(): UserDetailsService {
        val userDetails: UserDetails = User.builder()
            .username(swaggerUsername)
            .password(passwordEncoder().encode(swaggerPassword))
            .roles("USER")
            .build()
        
        return InMemoryUserDetailsManager(userDetails)
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        
        // 환경 변수에서 허용된 Origin 목록을 가져옴
        val origins = allowedOrigins.split(",").map { it.trim() }
        if (origins.contains("*")) {
            // 개발 환경: 모든 Origin 허용
            configuration.allowedOriginPatterns = listOf("*")
        } else {
            // 프로덕션 환경: 특정 Origin만 허용
            configuration.allowedOrigins = origins
        }
        
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        configuration.maxAge = 3600L // 1시간

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    /**
     * Swagger UI용 HTTP Basic Authentication EntryPoint
     */
    @Bean
    fun basicAuthenticationEntryPoint(): BasicAuthenticationEntryPoint {
        return BasicAuthenticationEntryPoint().apply {
            realmName = "Swagger UI"
        }
    }

    /**
     * 인증 실패 시 처리 (401 Unauthorized)
     * Swagger 경로는 HTTP Basic Authentication 팝업을 표시하고,
     * 기타 경로는 JSON 응답을 반환합니다.
     */
    @Bean
    fun authenticationEntryPoint(): AuthenticationEntryPoint {
        val basicAuthEntryPoint = basicAuthenticationEntryPoint()
        
        return AuthenticationEntryPoint { request, response, authException ->
            val path = (request as HttpServletRequest).requestURI
            
            // Swagger 경로는 HTTP Basic Authentication 팝업 표시
            if (path.startsWith("/swagger-ui") || 
                path.startsWith("/v3/api-docs") || 
                path.startsWith("/swagger-resources")) {
                basicAuthEntryPoint.commence(request, response, authException)
                return@AuthenticationEntryPoint
            }
            
            // 기타 경로는 JSON 응답 반환
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
