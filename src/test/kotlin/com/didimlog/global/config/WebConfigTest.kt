package com.didimlog.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WebConfig CORS 파싱 테스트")
class WebConfigTest {

    @Test
    @DisplayName("cors.allowed-origins 값을 콤마 기준으로 파싱한다")
    fun `parse allowed origins`() {
        val config = WebConfig(" http://localhost:5173, http://localhost:8080 , ,")

        val origins = config.parseAllowedOrigins(" http://localhost:5173, http://localhost:8080 , ,")

        assertThat(origins).containsExactly("http://localhost:5173", "http://localhost:8080")
    }
}


