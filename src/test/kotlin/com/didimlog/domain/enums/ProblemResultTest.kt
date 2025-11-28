package com.didimlog.domain.enums

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProblemResult Enum 테스트")
class ProblemResultTest {

    @Test
    @DisplayName("ProblemResult는 SUCCESS, FAIL, TIME_OVER 세 가지 값을 가진다")
    fun `ProblemResult 값 검증`() {
        // then
        assertThat(ProblemResult.values()).containsExactly(
            ProblemResult.SUCCESS,
            ProblemResult.FAIL,
            ProblemResult.TIME_OVER
        )
    }

    @Test
    @DisplayName("SUCCESS 값을 가져올 수 있다")
    fun `SUCCESS 값 가져오기`() {
        // when
        val result = ProblemResult.SUCCESS

        // then
        assertThat(result).isEqualTo(ProblemResult.SUCCESS)
    }

    @Test
    @DisplayName("FAIL 값을 가져올 수 있다")
    fun `FAIL 값 가져오기`() {
        // when
        val result = ProblemResult.FAIL

        // then
        assertThat(result).isEqualTo(ProblemResult.FAIL)
    }

    @Test
    @DisplayName("TIME_OVER 값을 가져올 수 있다")
    fun `TIME_OVER 값 가져오기`() {
        // when
        val result = ProblemResult.TIME_OVER

        // then
        assertThat(result).isEqualTo(ProblemResult.TIME_OVER)
    }
}
