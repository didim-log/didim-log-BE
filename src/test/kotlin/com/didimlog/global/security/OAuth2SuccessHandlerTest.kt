package com.didimlog.global.security

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
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

    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val oAuth2SuccessHandler = OAuth2SuccessHandler(
        jwtTokenProvider = jwtTokenProvider,
        studentRepository = studentRepository,
        frontendRedirectUri = "http://localhost:5173/oauth/callback"
    )

    @Test
    @DisplayName("provider+providerId로 사용자를 못 찾으면 회원가입 페이지로 리다이렉트한다 (email은 빈 문자열로 포함)")
    fun `신규 유저는 회원가입으로 리다이렉트`() {
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
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }

        assertThat(redirectUrl.captured).contains("isNewUser=true")
        assertThat(redirectUrl.captured).contains("provider=github")
        assertThat(redirectUrl.captured).contains("providerId=12345678")
        assertThat(redirectUrl.captured).contains("email=") // email이 없어도 파라미터는 항상 포함
    }

    @Test
    @DisplayName("provider+providerId로 사용자를 찾으면 JWT 토큰으로 로그인 처리한다")
    fun `기존 유저는 토큰으로 리다이렉트`() {
        // given
        val token = "test-jwt-token"
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
        every { jwtTokenProvider.createToken("boj_tester", Role.USER.value) } returns token

        val authentication = mockk<org.springframework.security.core.Authentication>()
        every { authentication.principal } returns oauth2User

        // when
        oAuth2SuccessHandler.onAuthenticationSuccess(request, response, authentication)

        // then
        verify(exactly = 1) { studentRepository.findByProviderAndProviderId(Provider.GITHUB, "12345678") }
        verify(exactly = 1) { jwtTokenProvider.createToken("boj_tester", Role.USER.value) }

        assertThat(redirectUrl.captured).contains("isNewUser=false")
        assertThat(redirectUrl.captured).contains("token=$token")
    }
}
