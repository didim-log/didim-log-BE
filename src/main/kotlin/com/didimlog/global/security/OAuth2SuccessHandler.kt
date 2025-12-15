package com.didimlog.global.security

import com.didimlog.domain.enums.Provider
import com.didimlog.domain.repository.StudentRepository
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
    private val studentRepository: StudentRepository,
    @Value("\${app.oauth.redirect-uri:http://localhost:5173/oauth/callback}")
    private val frontendRedirectUri: String
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oauth2User = authentication.principal as OAuth2User
        val providerValue = oauth2User.getAttribute<String>("provider") ?: ""
        val providerId = oauth2User.getAttribute<String>("providerId") ?: ""
        val email = oauth2User.getAttribute<String>("email") ?: ""

        val provider = Provider.from(providerValue)
            ?: throw IllegalStateException("유효하지 않은 provider 입니다. provider=$providerValue")

        val existingStudent = studentRepository.findByProviderAndProviderId(provider, providerId)
        if (existingStudent.isEmpty) {
            val targetUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("isNewUser", true)
                .queryParam("email", email)
                .queryParam("provider", providerValue)
                .queryParam("providerId", providerId)
                .build()
                .toUriString()

            clearAuthenticationAttributes(request)
            redirectStrategy.sendRedirect(request, response, targetUrl)
            return
        }

        val student = existingStudent.get()
        val tokenSubject = student.bojId?.value ?: student.id
            ?: throw IllegalStateException("토큰 subject를 만들 수 없습니다. studentId=${student.id}, provider=$providerValue, providerId=$providerId")

        val token = jwtTokenProvider.createToken(tokenSubject, student.role.value)
        val targetUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
            .queryParam("token", token)
            .queryParam("isNewUser", false)
            .build()
            .toUriString()

        clearAuthenticationAttributes(request)
        redirectStrategy.sendRedirect(request, response, targetUrl)
    }
}
