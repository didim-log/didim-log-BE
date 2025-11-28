package com.didimlog.domain.valueobject

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Nickname Value Object 테스트")
class NicknameTest {

    @Test
    @DisplayName("유효한 닉네임을 생성할 수 있다")
    fun `유효한 닉네임 생성`() {
        // given
        val validNickname = "testuser"

        // when
        val nickname = Nickname(validNickname)

        // then
        assertThat(nickname.value).isEqualTo(validNickname)
    }

    @Test
    @DisplayName("최소 길이(2자) 닉네임을 생성할 수 있다")
    fun `최소 길이 닉네임 생성`() {
        // given
        val minLengthNickname = "ab"

        // when
        val nickname = Nickname(minLengthNickname)

        // then
        assertThat(nickname.value).isEqualTo(minLengthNickname)
    }

    @Test
    @DisplayName("최대 길이(20자) 닉네임을 생성할 수 있다")
    fun `최대 길이 닉네임 생성`() {
        // given
        val maxLengthNickname = "a".repeat(20)

        // when
        val nickname = Nickname(maxLengthNickname)

        // then
        assertThat(nickname.value).isEqualTo(maxLengthNickname)
    }

    @Test
    @DisplayName("빈 문자열로 닉네임을 생성하면 예외가 발생한다")
    fun `빈 문자열 예외 발생`() {
        // given
        val emptyNickname = ""

        // when & then
        assertThatThrownBy { Nickname(emptyNickname) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("닉네임은 필수입니다.")
    }

    @Test
    @DisplayName("공백만 있는 문자열로 닉네임을 생성하면 예외가 발생한다")
    fun `공백만 있는 문자열 예외 발생`() {
        // given
        val blankNickname = "   "

        // when & then
        assertThatThrownBy { Nickname(blankNickname) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("닉네임은 필수입니다.")
    }

    @Test
    @DisplayName("1자 닉네임을 생성하면 예외가 발생한다")
    fun `1자 닉네임 예외 발생`() {
        // given
        val oneCharNickname = "a"

        // when & then
        assertThatThrownBy { Nickname(oneCharNickname) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("닉네임은 2자 이상 20자 이하여야 합니다.")
    }

    @Test
    @DisplayName("21자 닉네임을 생성하면 예외가 발생한다")
    fun `21자 닉네임 예외 발생`() {
        // given
        val tooLongNickname = "a".repeat(21)

        // when & then
        assertThatThrownBy { Nickname(tooLongNickname) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("닉네임은 2자 이상 20자 이하여야 합니다.")
    }
}
