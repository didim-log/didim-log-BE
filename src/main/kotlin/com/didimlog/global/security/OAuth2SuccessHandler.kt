package com.didimlog.global.security

import com.didimlog.global.auth.JwtTokenProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/**
 * OAuth2 소셜 로그인 성공 후 처리하는 핸들러
 * JWT 토큰을 생성하고 프론트엔드로 리다이렉트한다.
 */
@Component
class OAuth2SuccessHandler(
    private val jwtTokenProvider: JwtTokenProvider,
    @Value("\${app.oauth.redirect-uri:http://localhost:3000/oauth/callback}")
    private val frontendRedirectUri: String
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oauth2User = authentication.principal as OAuth2User
        val studentId = oauth2User.getAttribute<String>("studentId")
            ?: throw IllegalStateException("사용자 ID를 찾을 수 없습니다.")
        
        val isNewUser = oauth2User.getAttribute<Boolean>("isNewUser") ?: false
        val role = oauth2User.getAttribute<String>("role") ?: "GUEST"

        // JWT Access Token 생성 (role 정보 포함)
        val token = jwtTokenProvider.createToken(studentId, role)
        
        // 프론트엔드로 리다이렉트 (토큰과 신규 사용자 여부 포함)
        val targetUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
            .queryParam("token", token)
            .queryParam("isNewUser", isNewUser)
            .build()
            .toUriString()

        clearAuthenticationAttributes(request)
        redirectStrategy.sendRedirect(request, response, targetUrl)
    }
}
