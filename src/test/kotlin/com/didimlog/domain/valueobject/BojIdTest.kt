package com.didimlog.domain.valueobject

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BojId Value Object 테스트")
class BojIdTest {

    @Test
    @DisplayName("유효한 BOJ ID를 생성할 수 있다 (영문자만)")
    fun `영문자만 있는 BOJ ID 생성`() {
        // given
        val validBojId = "testuser"

        // when
        val bojId = BojId(validBojId)

        // then
        assertThat(bojId.value).isEqualTo(validBojId)
    }

    @Test
    @DisplayName("숫자가 포함된 BOJ ID를 생성할 수 있다")
    fun `숫자 포함 BOJ ID 생성`() {
        // given
        val bojIdWithNumber = "user123"

        // when
        val bojId = BojId(bojIdWithNumber)

        // then
        assertThat(bojId.value).isEqualTo(bojIdWithNumber)
    }

    @Test
    @DisplayName("언더스코어가 포함된 BOJ ID를 생성할 수 있다")
    fun `언더스코어 포함 BOJ ID 생성`() {
        // given
        val bojIdWithUnderscore = "test_user"

        // when
        val bojId = BojId(bojIdWithUnderscore)

        // then
        assertThat(bojId.value).isEqualTo(bojIdWithUnderscore)
    }

    @Test
    @DisplayName("대소문자, 숫자, 언더스코어가 혼합된 BOJ ID를 생성할 수 있다")
    fun `혼합 문자 BOJ ID 생성`() {
        // given
        val mixedBojId = "TestUser_123"

        // when
        val bojId = BojId(mixedBojId)

        // then
        assertThat(bojId.value).isEqualTo(mixedBojId)
    }

    @Test
    @DisplayName("공백이 포함된 BOJ ID를 생성하면 예외가 발생한다")
    fun `공백 포함 BOJ ID 예외 발생`() {
        // given
        val bojIdWithSpace = "test user"

        // when & then
        assertThatThrownBy { BojId(bojIdWithSpace) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("유효하지 않은 BOJ ID 형식입니다.")
    }

    @Test
    @DisplayName("하이픈이 포함된 BOJ ID를 생성하면 예외가 발생한다")
    fun `하이픈 포함 BOJ ID 예외 발생`() {
        // given
        val bojIdWithHyphen = "test-user"

        // when & then
        assertThatThrownBy { BojId(bojIdWithHyphen) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("유효하지 않은 BOJ ID 형식입니다.")
    }

    @Test
    @DisplayName("특수문자가 포함된 BOJ ID를 생성하면 예외가 발생한다")
    fun `특수문자 포함 BOJ ID 예외 발생`() {
        // given
        val bojIdWithSpecialChar = "test@user"

        // when & then
        assertThatThrownBy { BojId(bojIdWithSpecialChar) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("유효하지 않은 BOJ ID 형식입니다.")
    }

    @Test
    @DisplayName("빈 문자열로 BOJ ID를 생성하면 예외가 발생한다")
    fun `빈 문자열 BOJ ID 예외 발생`() {
        // given
        val emptyBojId = ""

        // when & then
        assertThatThrownBy { BojId(emptyBojId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("유효하지 않은 BOJ ID 형식입니다.")
    }
}
