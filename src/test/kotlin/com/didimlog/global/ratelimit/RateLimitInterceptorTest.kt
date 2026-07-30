package com.didimlog.global.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequestWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.time.ZonedDateTime
import java.util.stream.Stream

@DisplayName("Rate Limit 인터셉터 테스트")
class RateLimitInterceptorTest {

    private val rateLimitService: RateLimitService = mockk()
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val interceptor = RateLimitInterceptor(rateLimitService, objectMapper)

    @BeforeEach
    fun setUp() {
        clearMocks(rateLimitService)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rateLimitedRoutes")
    @DisplayName("인증 경로별 정책으로 POST 요청을 제한한다")
    fun `applies policy for each protected auth route`(
        path: String,
        expectedKeyPrefix: String,
        limit: Int,
        windowMinutes: Int
    ) {
        every {
            rateLimitService.checkAndRecord("$expectedKeyPrefix:198.51.100.20", limit, windowMinutes)
        } returns RateLimitDecision(
            allowed = true,
            limit = limit,
            remainingRequests = limit - 1,
            retryAfterSeconds = null
        )
        val request = request("POST", path)
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertThat(allowed).isTrue()
        assertThat(response.getHeader("X-Rate-Limit-Limit")).isEqualTo(limit.toString())
        assertThat(response.getHeader("X-Rate-Limit-Remaining")).isEqualTo((limit - 1).toString())
        verify(exactly = 1) {
            rateLimitService.checkAndRecord("$expectedKeyPrefix:198.51.100.20", limit, windowMinutes)
        }
    }

    @Test
    @DisplayName("전달 IP 헤더 대신 실제 연결 주소를 키로 사용한다")
    fun `ignores spoofed forwarding headers`() {
        every {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        } returns RateLimitDecision(true, 10, 9, null)
        val request = request("POST", "/api/v1/auth/login").apply {
            addHeader("X-Forwarded-For", "203.0.113.10")
            addHeader("X-Real-IP", "203.0.113.11")
        }

        val allowed = interceptor.preHandle(request, MockHttpServletResponse(), Any())

        assertThat(allowed).isTrue()
        verify(exactly = 1) {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        }
    }

    @Test
    @DisplayName("인코딩된 요청 URI도 서버가 매칭한 로그인 경로 정책으로 제한한다")
    fun `uses the matched route for an encoded request URI`() {
        every {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        } returns RateLimitDecision(true, 10, 9, null)
        val request = request(
            method = "POST",
            path = "/api/v1/auth/log%69n",
            matchedPattern = "/api/v1/auth/login"
        )

        val allowed = interceptor.preHandle(request, MockHttpServletResponse(), Any())

        assertThat(allowed).isTrue()
        verify(exactly = 1) {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        }
    }

    @Test
    @DisplayName("서버가 신뢰 프록시를 거쳐 정규화한 주소를 키로 사용한다")
    fun `uses the server processed remote address`() {
        every {
            rateLimitService.checkAndRecord("login:203.0.113.10", 10, 60)
        } returns RateLimitDecision(true, 10, 9, null)
        val rawRequest = request("POST", "/api/v1/auth/login")
        val serverProcessedRequest = object : HttpServletRequestWrapper(rawRequest) {
            override fun getRemoteAddr(): String = "203.0.113.10"
        }

        val allowed = interceptor.preHandle(serverProcessedRequest, MockHttpServletResponse(), Any())

        assertThat(allowed).isTrue()
        verify(exactly = 1) {
            rateLimitService.checkAndRecord("login:203.0.113.10", 10, 60)
        }
    }

    @ParameterizedTest
    @MethodSource("unlimitedRequests")
    @DisplayName("POST가 아니거나 정책에 없는 인증 경로는 제한하지 않는다")
    fun `skips methods and routes without a policy`(method: String, path: String) {
        val allowed = interceptor.preHandle(
            request(method, path),
            MockHttpServletResponse(),
            Any()
        )

        assertThat(allowed).isTrue()
        verify(exactly = 0) {
            rateLimitService.checkAndRecord(any(), any(), any())
        }
    }

    @Test
    @DisplayName("관리자도 로그인 요청 제한을 적용받는다")
    fun `does not bypass an admin request`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "admin",
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
        )
        every {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        } returns RateLimitDecision(true, 10, 9, null)

        val allowed = interceptor.preHandle(
            request("POST", "/api/v1/auth/login"),
            MockHttpServletResponse(),
            Any()
        )

        assertThat(allowed).isTrue()
        verify(exactly = 1) {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        }
    }

    @Test
    @DisplayName("로그인 허용 결과를 컨트롤러가 읽을 수 있도록 요청에 저장한다")
    fun `stores login decision as a request attribute`() {
        val decision = RateLimitDecision(true, 10, 8, null)
        every {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        } returns decision
        val request = request("POST", "/api/v1/auth/login")

        val allowed = interceptor.preHandle(request, MockHttpServletResponse(), Any())

        assertThat(allowed).isTrue()
        assertThat(request.getAttribute(RateLimitInterceptor.RATE_LIMIT_DECISION_ATTRIBUTE))
            .isSameAs(decision)
    }

    @Test
    @DisplayName("요청 한도를 넘으면 재시도 정보와 공통 오류 응답을 반환한다")
    fun `returns retry metadata when request is blocked`() {
        every {
            rateLimitService.checkAndRecord("login:198.51.100.20", 10, 60)
        } returns RateLimitDecision(
            allowed = false,
            limit = 10,
            remainingRequests = 0,
            retryAfterSeconds = 42
        )
        val request = request("POST", "/api/v1/auth/login")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertThat(allowed).isFalse()
        assertThat(response.status).isEqualTo(429)
        assertThat(response.contentType).startsWith("application/json")
        assertThat(response.getHeader("X-Rate-Limit-Limit")).isEqualTo("10")
        assertThat(response.getHeader("X-Rate-Limit-Remaining")).isEqualTo("0")
        assertThat(response.getHeader("Retry-After")).isEqualTo("42")

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["status"].asInt()).isEqualTo(429)
        assertThat(body["code"].asText()).isEqualTo("RATE_LIMIT_EXCEEDED")
        assertThat(body["remainingAttempts"].asInt()).isZero()
        assertThat(ZonedDateTime.parse(body["unlockTime"].asText()).zone.id)
            .isEqualTo("+09:00")
    }

    private fun request(
        method: String,
        path: String,
        matchedPattern: String = path
    ): MockHttpServletRequest {
        return MockHttpServletRequest(method, path).apply {
            remoteAddr = "198.51.100.20"
            setAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                matchedPattern
            )
        }
    }

    companion object {
        @JvmStatic
        fun rateLimitedRoutes(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/auth/signup", "signup", 5, 60),
            Arguments.of("/api/v1/auth/super-admin", "signup", 5, 60),
            Arguments.of("/api/v1/auth/login", "login", 10, 60),
            Arguments.of("/api/v1/auth/find-account", "password_reset", 3, 60),
            Arguments.of("/api/v1/auth/find-id", "password_reset", 3, 60),
            Arguments.of("/api/v1/auth/find-password", "password_reset", 3, 60),
            Arguments.of("/api/v1/auth/reset-password", "password_reset", 3, 60),
            Arguments.of("/api/v1/auth/boj/verify", "boj_verify", 10, 1)
        )

        @JvmStatic
        fun unlimitedRequests(): Stream<Arguments> = Stream.of(
            Arguments.of("GET", "/api/v1/auth/login"),
            Arguments.of("OPTIONS", "/api/v1/auth/login"),
            Arguments.of("POST", "/api/v1/auth/oauth/exchange"),
            Arguments.of("POST", "/api/v1/auth/refresh"),
            Arguments.of("POST", "/api/v1/auth/boj/code"),
            Arguments.of("POST", "/api/v1/auth/signup/finalize")
        )
    }
}
