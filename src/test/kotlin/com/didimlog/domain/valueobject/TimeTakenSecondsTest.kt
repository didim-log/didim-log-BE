package com.didimlog.domain.valueobject

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TimeTakenSeconds Value Object 테스트")
class TimeTakenSecondsTest {

    @Test
    @DisplayName("0 이상인 값으로 TimeTakenSeconds를 생성할 수 있다")
    fun `0 이상 값 생성`() {
        val time = TimeTakenSeconds(0L)

        assertThat(time.value).isEqualTo(0L)
    }

    @Test
    @DisplayName("음수 값으로 생성하면 예외가 발생한다")
    fun `음수 값 예외 발생`() {
        assertThatThrownBy { TimeTakenSeconds(-1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("풀이 시간은 0초 이상이어야 합니다.")
    }
}


