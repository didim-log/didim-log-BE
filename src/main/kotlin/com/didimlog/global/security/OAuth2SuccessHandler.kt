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
 * 기존 유저는 JWT 토큰을 발급하고, 신규 유저는 가입 마무리를 위한 정보를 쿼리 파라미터로 전달한다.
 */
@Component
class OAuth2SuccessHandler(
    private val jwtTokenProvider: JwtTokenProvider,
    @Value("\${app.oauth.redirect-uri:http://localhost:5173/oauth/callback}")
    private val frontendRedirectUri: String
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oauth2User = authentication.principal as OAuth2User
        val studentId = oauth2User.getAttribute<String>("studentId")
        val isNewUser = oauth2User.getAttribute<Boolean>("isNewUser") ?: false
        val provider = oauth2User.getAttribute<String>("provider") ?: ""
        val providerId = oauth2User.getAttribute<String>("providerId") ?: ""
        val email = oauth2User.getAttribute<String>("email")

        val targetUrl = if (isNewUser) {
            // 신규 유저: 가입 마무리를 위한 정보를 쿼리 파라미터로 전달
            UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("isNewUser", true)
                .apply {
                    email?.let { queryParam("email", it) }
                    queryParam("provider", provider)
                    queryParam("providerId", providerId)
                }
                .build()
                .toUriString()
        } else {
            // 기존 유저: JWT 토큰 발급 및 리다이렉트
            val role = oauth2User.getAttribute<String>("role") ?: "GUEST"
            val token = jwtTokenProvider.createToken(studentId!!, role)
            
            UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("token", token)
                .queryParam("isNewUser", false)
                .build()
                .toUriString()
        }

        clearAuthenticationAttributes(request)
        redirectStrategy.sendRedirect(request, response, targetUrl)
    }
}
