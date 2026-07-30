package com.didimlog.global.util

import jakarta.servlet.http.HttpServletRequestWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

@DisplayName("HTTP 요청 유틸리티 테스트")
class HttpRequestUtilTest {

    @Test
    @DisplayName("서버가 신뢰 프록시를 거쳐 정규화한 주소를 반환한다")
    fun `getClientIpAddress uses the server processed remote address`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "198.51.100.20"
            addHeader("X-Forwarded-For", "203.0.113.10")
            addHeader("X-Real-IP", "203.0.113.11")
        }
        val serverProcessedRequest = object : HttpServletRequestWrapper(request) {
            override fun getRemoteAddr(): String = "203.0.113.10"
        }

        val result = HttpRequestUtil.getClientIpAddress(serverProcessedRequest)

        assertThat(result).isEqualTo("203.0.113.10")
    }

    @Test
    @DisplayName("전달 헤더를 직접 해석하지 않는다")
    fun `getClientIpAddress does not parse forwarding headers`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "198.51.100.20"
            addHeader("X-Forwarded-For", "203.0.113.10")
            addHeader("X-Real-IP", "203.0.113.11")
        }

        assertThat(HttpRequestUtil.getClientIpAddress(request))
            .isEqualTo("198.51.100.20")
    }
}
