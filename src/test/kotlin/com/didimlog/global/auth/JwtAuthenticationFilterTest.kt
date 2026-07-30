package com.didimlog.global.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.charset.StandardCharsets
import java.util.Date

@DisplayName("JwtAuthenticationFilter 테스트")
class JwtAuthenticationFilterTest {

    private val secret = "test-secret-key-for-jwt-authentication-filter-test-12345678901234567890"
    private val expiration = 3600000L
    private val jwtTokenProvider = JwtTokenProvider(secret, expiration, 604800000L)
    private val jwtTokenProviderProvider = mockk<ObjectProvider<JwtTokenProvider>>()
    private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        every { jwtTokenProviderProvider.getIfAvailable() } returns jwtTokenProvider
        filter = JwtAuthenticationFilter(jwtTokenProviderProvider)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("USER Access Token은 사용자 인증을 생성한다")
    fun `USER Access Token 인증 성공`() {
        val token = jwtTokenProvider.createToken("testuser", "USER")
        val filterChain = mockk<FilterChain>(relaxed = true)
        val request = bearerRequest(token)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertThat(authentication.name).isEqualTo("testuser")
        assertThat(authentication.authorities.map { it.authority }).containsExactly("ROLE_USER")
        verify(exactly = 1) { filterChain.doFilter(request, response) }
    }

    @Test
    @DisplayName("Refresh Token은 사용자 인증을 생성하지 않는다")
    fun `Refresh Token 인증 거부`() {
        val token = jwtTokenProvider.createRefreshToken("testuser")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("type이 없는 기존 토큰은 사용자 인증을 생성하지 않는다")
    fun `type 없는 토큰 인증 거부`() {
        val token = createSignedToken(role = "USER")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("role이 없는 Access Token은 사용자 인증을 생성하지 않는다")
    fun `role 없는 Access Token 인증 거부`() {
        val token = createSignedToken(type = "access")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("허용되지 않은 role의 Access Token은 사용자 인증을 생성하지 않는다")
    fun `허용되지 않은 role 인증 거부`() {
        val token = createSignedToken(type = "access", role = "GUEST")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("등록되지 않은 role의 Access Token은 사용자 인증을 생성하지 않는다")
    fun `등록되지 않은 role 인증 거부`() {
        val token = createSignedToken(type = "access", role = "ROOT")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("subject가 빈 Access Token은 사용자 인증을 생성하지 않는다")
    fun `빈 subject 인증 거부`() {
        val token = createSignedToken(subject = " ", type = "access", role = "USER")

        assertTokenDoesNotAuthenticate(token)
    }

    private fun assertTokenDoesNotAuthenticate(token: String) {
        val filterChain = mockk<FilterChain>(relaxed = true)
        val request = bearerRequest(token)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(exactly = 1) { filterChain.doFilter(request, response) }
    }

    private fun bearerRequest(token: String): MockHttpServletRequest {
        return MockHttpServletRequest().apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
    }

    private fun createSignedToken(
        subject: String = "testuser",
        type: String? = null,
        role: String? = null
    ): String {
        val builder = Jwts.builder()
            .subject(subject)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expiration))

        if (type != null) {
            builder.claim("type", type)
        }
        if (role != null) {
            builder.claim("role", role)
        }

        return builder
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8)))
            .compact()
    }
}
