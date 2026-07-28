package com.didimlog.global.util

import jakarta.servlet.http.HttpServletRequestWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

@DisplayName("HTTP 요청 유틸리티 테스트")
class HttpRequestUtilTest {

    @Test
    @DisplayName("전달 헤더 래퍼를 벗겨 실제 연결 주소를 반환한다")
    fun `getConnectionRemoteAddress unwraps forwarded request`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "198.51.100.20"
            addHeader("X-Forwarded-For", "203.0.113.10")
        }
        val forwardedWrapper = object : HttpServletRequestWrapper(request) {
            override fun getRemoteAddr(): String = "203.0.113.10"
        }
        val nestedWrapper = HttpServletRequestWrapper(forwardedWrapper)

        val result = HttpRequestUtil.getConnectionRemoteAddress(nestedWrapper)

        assertThat(result).isEqualTo("198.51.100.20")
    }
}
