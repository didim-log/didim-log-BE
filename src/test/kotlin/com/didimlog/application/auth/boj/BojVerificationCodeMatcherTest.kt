package com.didimlog.application.auth.boj

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BOJ 인증 코드 매칭 규칙 테스트")
class BojVerificationCodeMatcherTest {

    private val matcher = BojVerificationCodeMatcher()

    @Test
    @DisplayName("상태 메시지와 코드가 완전히 같으면 true")
    fun `matches exact`() {
        val result = matcher.matches(
            BojProfileStatusMessage("DIDIM-LOG-ABC123"),
            BojVerificationCode("DIDIM-LOG-ABC123")
        )

        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("부분 문자열로만 포함되는 경우(ABC123이 ABC1234에 포함) false")
    fun `does not match by substring`() {
        val result = matcher.matches(
            BojProfileStatusMessage("DIDIM-LOG-ABC1234"),
            BojVerificationCode("DIDIM-LOG-ABC123")
        )

        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("공백/문장부호로 구분되어 포함되면 true")
    fun `matches with boundary`() {
        val result = matcher.matches(
            BojProfileStatusMessage("코드: DIDIM-LOG-ABC123 입니다."),
            BojVerificationCode("DIDIM-LOG-ABC123")
        )

        assertThat(result).isTrue()
    }
}


