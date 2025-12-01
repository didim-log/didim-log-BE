package com.didimlog.domain.valueobject

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProblemId Value Object 테스트")
class ProblemIdTest {

    @Test
    @DisplayName("공백이 아닌 문자열로 ProblemId를 생성할 수 있다")
    fun `유효한 ProblemId 생성`() {
        val problemId = ProblemId("1000")

        assertThat(problemId.value).isEqualTo("1000")
    }

    @Test
    @DisplayName("빈 문자열이면 예외가 발생한다")
    fun `빈 문자열 예외 발생`() {
        assertThatThrownBy { ProblemId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("문제 ID는 필수입니다.")
    }

    @Test
    @DisplayName("공백만 있는 문자열이면 예외가 발생한다")
    fun `공백 문자열 예외 발생`() {
        assertThatThrownBy { ProblemId("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("문제 ID는 필수입니다.")
    }
}


