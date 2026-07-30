package com.didimlog.global.security

import com.didimlog.application.auth.oauth.OAuthExchangeService
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.repository.StudentRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/**
 * OAuth2 소셜 로그인 성공 후 처리하는 핸들러
 * 기존 유저는 짧게 유지되는 일회용 코드를 발급하고, 신규 유저는 일반 회원가입으로 안내한다.
 */
@Component
class OAuth2SuccessHandler(
    private val oAuthExchangeService: OAuthExchangeService,
    private val studentRepository: StudentRepository,
    @Value("\${cors.oauth.redirect-uri:http://localhost:5173/oauth/callback}")
    private val frontendRedirectUri: String
) : SimpleUrlAuthenticationSuccessHandler() {

    private val log = LoggerFactory.getLogger(OAuth2SuccessHandler::class.java)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oauth2User = authentication.principal as OAuth2User
        val providerValue = oauth2User.getAttribute<String>("provider") ?: ""
        val providerId = oauth2User.getAttribute<String>("providerId") ?: ""

        val provider = Provider.from(providerValue)
        val targetUrl = when {
            provider == null || provider == Provider.BOJ || providerId.isBlank() ->
                errorRedirect("invalid_provider")
            else -> {
                val student = studentRepository.findByProviderAndProviderId(provider, providerId)
                    .orElse(null)
                when {
                    student == null || student.bojId == null || student.role == Role.GUEST ->
                        errorRedirect("oauth_signup_not_supported")
                    student.id.isNullOrBlank() ->
                        errorRedirect("oauth_login_failed")
                    else -> {
                        try {
                            val code = oAuthExchangeService.issue(student.id)
                            UriComponentsBuilder.fromUriString(frontendRedirectUri)
                                .queryParam("code", code)
                                .build()
                                .toUriString()
                        } catch (exception: RuntimeException) {
                            log.error("OAuth 교환 코드 발급에 실패했습니다.", exception)
                            errorRedirect("oauth_login_failed")
                        }
                    }
                }
            }
        }

        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("Referrer-Policy", "no-referrer")
        clearAuthenticationAttributes(request)
        redirectStrategy.sendRedirect(request, response, targetUrl)
    }

    private fun errorRedirect(error: String): String {
        return UriComponentsBuilder.fromUriString(frontendRedirectUri)
            .queryParam("error", error)
            .build()
            .toUriString()
    }
}
