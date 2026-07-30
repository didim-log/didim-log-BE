package com.didimlog.global.auth

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
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
import java.util.Optional

@DisplayName("JwtAuthenticationFilter 테스트")
class JwtAuthenticationFilterTest {

    private val secret = "test-secret-key-for-jwt-authentication-filter-test-12345678901234567890"
    private val expiration = 3600000L
    private val jwtTokenProvider = JwtTokenProvider(secret, expiration, 604800000L)
    private val jwtTokenProviderProvider = mockk<ObjectProvider<JwtTokenProvider>>()
    private val studentRepository = mockk<StudentRepository>()
    private val studentRepositoryProvider = mockk<ObjectProvider<StudentRepository>>()
    private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        every { jwtTokenProviderProvider.getIfAvailable() } returns jwtTokenProvider
        every { studentRepositoryProvider.getIfAvailable() } returns studentRepository
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student())
        filter = JwtAuthenticationFilter(jwtTokenProviderProvider, studentRepositoryProvider)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("USER Access Token은 불변 학생 ID 인증을 생성한다")
    fun `USER Access Token 인증 성공`() {
        val token = jwtTokenProvider.createToken("testuser", STUDENT_ID, 3, "USER")
        val filterChain = mockk<FilterChain>(relaxed = true)
        val request = bearerRequest(token)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertThat(authentication.name).isEqualTo(STUDENT_ID)
        assertThat(authentication.authorities.map { it.authority }).containsExactly("ROLE_USER")
        verify(exactly = 1) { filterChain.doFilter(request, response) }
    }

    @Test
    @DisplayName("Refresh Token은 사용자 인증을 생성하지 않는다")
    fun `Refresh Token 인증 거부`() {
        val token = jwtTokenProvider.createRefreshToken("testuser", "student-id", credentialVersion = 0)

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("type이 없는 기존 토큰은 사용자 인증을 생성하지 않는다")
    fun `type 없는 토큰 인증 거부`() {
        val token = createSignedToken(role = "USER", studentId = STUDENT_ID, credentialVersion = 3)

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("role이 없는 Access Token은 사용자 인증을 생성하지 않는다")
    fun `role 없는 Access Token 인증 거부`() {
        val token = createSignedToken(type = "access", studentId = STUDENT_ID, credentialVersion = 3)

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("허용되지 않은 role의 Access Token은 사용자 인증을 생성하지 않는다")
    fun `허용되지 않은 role 인증 거부`() {
        val token = createSignedToken(
            type = "access",
            role = "GUEST",
            studentId = STUDENT_ID,
            credentialVersion = 3
        )

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("등록되지 않은 role의 Access Token은 사용자 인증을 생성하지 않는다")
    fun `등록되지 않은 role 인증 거부`() {
        val token = createSignedToken(
            type = "access",
            role = "ROOT",
            studentId = STUDENT_ID,
            credentialVersion = 3
        )

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("subject가 빈 Access Token은 사용자 인증을 생성하지 않는다")
    fun `빈 subject 인증 거부`() {
        val token = createSignedToken(
            subject = " ",
            type = "access",
            role = "USER",
            studentId = STUDENT_ID,
            credentialVersion = 3
        )

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("학생 ID가 없는 기존 Access Token은 사용자 인증을 생성하지 않는다")
    fun `학생 ID 없는 Access Token 인증 거부`() {
        val token = createSignedToken(type = "access", role = "USER", credentialVersion = 3)

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("자격 증명 버전이 없는 기존 Access Token은 사용자 인증을 생성하지 않는다")
    fun `자격 증명 버전 없는 Access Token 인증 거부`() {
        val token = createSignedToken(type = "access", role = "USER", studentId = STUDENT_ID)

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("현재 학생을 찾을 수 없는 Access Token은 사용자 인증을 생성하지 않는다")
    fun `삭제된 학생 Access Token 인증 거부`() {
        every { studentRepository.findById("deleted-student") } returns Optional.empty()
        val token = jwtTokenProvider.createToken("testuser", "deleted-student", 3, "USER")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("현재 BOJ ID와 다른 Access Token은 사용자 인증을 생성하지 않는다")
    fun `변경 전 BOJ ID Access Token 인증 거부`() {
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student(bojId = "changeduser"))
        val token = jwtTokenProvider.createToken("testuser", STUDENT_ID, 3, "USER")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("현재 자격 증명 버전과 다른 Access Token은 사용자 인증을 생성하지 않는다")
    fun `변경 전 자격 증명 버전 Access Token 인증 거부`() {
        every {
            studentRepository.findById(STUDENT_ID)
        } returns Optional.of(student(credentialVersion = 4))
        val token = jwtTokenProvider.createToken("testuser", STUDENT_ID, 3, "USER")

        assertTokenDoesNotAuthenticate(token)
    }

    @Test
    @DisplayName("현재 권한과 다른 Access Token은 사용자 인증을 생성하지 않는다")
    fun `변경 전 권한 Access Token 인증 거부`() {
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student(role = Role.ADMIN))
        val token = jwtTokenProvider.createToken("testuser", STUDENT_ID, 3, "USER")

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
        role: String? = null,
        studentId: String? = null,
        credentialVersion: Long? = null
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
        if (studentId != null) {
            builder.claim("studentId", studentId)
        }
        if (credentialVersion != null) {
            builder.claim("credentialVersion", credentialVersion)
        }

        return builder
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8)))
            .compact()
    }

    private fun student(
        bojId: String = "testuser",
        credentialVersion: Long = 3,
        role: Role = Role.USER
    ): Student {
        return Student(
            id = STUDENT_ID,
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            credentialVersion = credentialVersion,
            currentTier = Tier.BRONZE,
            role = role
        )
    }

    private companion object {
        const val STUDENT_ID = "student-id"
    }
}
