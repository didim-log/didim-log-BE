package com.didimlog.global.security

import com.didimlog.application.auth.oauth.OAuthExchangeService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import io.mockk.Runs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.just
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import java.util.Optional

@DisplayName("OAuth2SuccessHandler 테스트")
class OAuth2SuccessHandlerTest {

    private val oAuthExchangeService: OAuthExchangeService = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val oAuth2SuccessHandler = OAuth2SuccessHandler(
        oAuthExchangeService = oAuthExchangeService,
        studentRepository = studentRepository,
        frontendRedirectUri = "http://localhost:5173/oauth/callback"
    )

    @Test
    @DisplayName("신규 사용자는 개인정보 없이 지원 중단 오류로 리다이렉트한다")
    fun `신규 유저는 일반 가입 안내 오류로 리다이렉트`() {
        // given
        val attributes = mapOf<String, Any>(
            "id" to "12345678", // nameAttributeKey로 사용
            "provider" to "github",
            "providerId" to "12345678",
            "isNewUser" to true // CustomOAuth2UserService가 넣을 수 있으나, SuccessHandler는 repo 조회 결과를 사용한다
        )

        val oauth2User: OAuth2User = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_GUEST")),
            attributes,
            "id"
        )

        val request: HttpServletRequest = mockk(relaxed = true)
        val response: HttpServletResponse = mockk(relaxed = true)
        every { request.getSession(false) } returns null

        val redirectUrl = slot<String>()
        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        every { response.sendRedirect(capture(redirectUrl)) } just Runs

        every { studentRepository.findByProviderAndProviderId(Provider.GITHUB, "12345678") } returns Optional.empty()

        val authentication = mockk<org.springframework.security.core.Authentication>()
        every { authentication.principal } returns oauth2User

        // when
        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication)

        // then
        verify(exactly = 1) { studentRepository.findByProviderAndProviderId(Provider.GITHUB, "12345678") }
        verify(exactly = 0) { oAuthExchangeService.issue(any()) }

        assertThat(redirectUrl.captured).contains("error=oauth_signup_not_supported")
        assertThat(redirectUrl.captured).doesNotContain("providerId")
        assertThat(redirectUrl.captured).doesNotContain("email")
        assertThat(redirectUrl.captured).doesNotContain("token")
    }

    @Test
    @DisplayName("기존 사용자는 일회용 코드만 포함해 리다이렉트한다")
    fun `기존 유저는 교환 코드로 리다이렉트`() {
        // given
        val code = "single-use-code"
        val attributes = mapOf<String, Any>(
            "id" to "12345678", // nameAttributeKey로 사용
            "provider" to "github",
            "providerId" to "12345678"
        )

        val oauth2User: OAuth2User = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_GUEST")),
            attributes,
            "id"
        )

        val request: HttpServletRequest = mockk(relaxed = true)
        val response: HttpServletResponse = mockk(relaxed = true)
        every { request.getSession(false) } returns null

        val redirectUrl = slot<String>()
        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        every { response.sendRedirect(capture(redirectUrl)) } just Runs

        val student = Student(
            id = "mongo-id-1",
            nickname = Nickname("tester"),
            provider = Provider.GITHUB,
            providerId = "12345678",
            bojId = BojId("boj_tester"),
            currentTier = Tier.BRONZE,
            role = Role.USER,
            termsAgreed = true
        )

        every { studentRepository.findByProviderAndProviderId(Provider.GITHUB, "12345678") } returns Optional.of(student)
        every { oAuthExchangeService.issue("mongo-id-1") } returns code

        val authentication = mockk<org.springframework.security.core.Authentication>()
        every { authentication.principal } returns oauth2User

        // when
        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication)

        // then
        verify(exactly = 1) { studentRepository.findByProviderAndProviderId(Provider.GITHUB, "12345678") }
        verify(exactly = 1) { oAuthExchangeService.issue("mongo-id-1") }

        assertThat(redirectUrl.captured).isEqualTo(
            "http://localhost:5173/oauth/callback?code=$code"
        )
        assertThat(redirectUrl.captured).doesNotContain("token")
        assertThat(redirectUrl.captured).doesNotContain("refreshToken")
        assertThat(redirectUrl.captured).doesNotContain("providerId")
        assertThat(redirectUrl.captured).doesNotContain("email")
        verify(exactly = 1) { response.setHeader("Cache-Control", "no-store") }
        verify(exactly = 1) { response.setHeader("Pragma", "no-cache") }
        verify(exactly = 1) { response.setHeader("Referrer-Policy", "no-referrer") }
    }

    @Test
    @DisplayName("교환 코드 발급 실패는 세부 원인을 노출하지 않고 로그인 실패로 리다이렉트한다")
    fun `교환 코드 발급 실패 처리`() {
        val attributes = mapOf<String, Any>(
            "id" to "provider-user-id",
            "provider" to "github",
            "providerId" to "provider-user-id"
        )
        val oauth2User: OAuth2User = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            attributes,
            "id"
        )
        val student = Student(
            id = "mongo-id-issue-failure",
            nickname = Nickname("issue-user"),
            provider = Provider.GITHUB,
            providerId = "provider-user-id",
            bojId = BojId("issue_failure_boj"),
            currentTier = Tier.BRONZE,
            role = Role.USER,
            termsAgreed = true
        )
        val request: HttpServletRequest = mockk(relaxed = true)
        val response: HttpServletResponse = mockk(relaxed = true)
        val redirectUrl = slot<String>()
        every { request.getSession(false) } returns null
        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        every { response.sendRedirect(capture(redirectUrl)) } just Runs
        every {
            studentRepository.findByProviderAndProviderId(Provider.GITHUB, "provider-user-id")
        } returns Optional.of(student)
        every {
            oAuthExchangeService.issue("mongo-id-issue-failure")
        } throws IllegalStateException("Redis unavailable")
        val authentication = mockk<org.springframework.security.core.Authentication>()
        every { authentication.principal } returns oauth2User

        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication)

        assertThat(redirectUrl.captured).isEqualTo(
            "http://localhost:5173/oauth/callback?error=oauth_login_failed"
        )
        assertThat(redirectUrl.captured).doesNotContain("Redis")
        assertThat(redirectUrl.captured).doesNotContain("mongo-id-issue-failure")
        assertThat(redirectUrl.captured).doesNotContain("token")
        verify(exactly = 1) { oAuthExchangeService.issue("mongo-id-issue-failure") }
        verify(exactly = 1) { response.setHeader("Cache-Control", "no-store") }
    }

    @Test
    @DisplayName("BOJ ID가 있어도 GUEST 사용자는 교환 코드를 발급하지 않는다")
    fun `GUEST 사용자는 교환 코드 발급 거절`() {
        val attributes = mapOf<String, Any>(
            "id" to "guest-provider-id",
            "provider" to "github",
            "providerId" to "guest-provider-id"
        )
        val oauth2User: OAuth2User = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_GUEST")),
            attributes,
            "id"
        )
        val guest = Student(
            id = "mongo-id-guest",
            nickname = Nickname("guest-user"),
            provider = Provider.GITHUB,
            providerId = "guest-provider-id",
            bojId = BojId("guest_boj"),
            currentTier = Tier.BRONZE,
            role = Role.GUEST,
            termsAgreed = false
        )
        val request: HttpServletRequest = mockk(relaxed = true)
        val response: HttpServletResponse = mockk(relaxed = true)
        val redirectUrl = slot<String>()
        every { request.getSession(false) } returns null
        every { response.encodeRedirectURL(any()) } answers { firstArg() }
        every { response.sendRedirect(capture(redirectUrl)) } just Runs
        every {
            studentRepository.findByProviderAndProviderId(Provider.GITHUB, "guest-provider-id")
        } returns Optional.of(guest)
        val authentication = mockk<org.springframework.security.core.Authentication>()
        every { authentication.principal } returns oauth2User

        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication)

        assertThat(redirectUrl.captured).isEqualTo(
            "http://localhost:5173/oauth/callback?error=oauth_signup_not_supported"
        )
        assertThat(redirectUrl.captured).doesNotContain("guest-provider-id")
        verify(exactly = 0) { oAuthExchangeService.issue(any()) }
    }
}
