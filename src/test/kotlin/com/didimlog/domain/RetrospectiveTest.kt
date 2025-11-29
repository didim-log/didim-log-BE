package com.didimlog.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Retrospective 도메인 테스트")
class RetrospectiveTest {

    @Test
    @DisplayName("회고 내용이 10자 이상이면 정상적으로 생성된다")
    fun `유효한 길이의 회고 내용이면 생성 성공`() {
        // when
        val retrospective = Retrospective(
            studentId = "student-id",
            problemId = "problem-id",
            content = "충분히 긴 회고 내용입니다."
        )

        // then
        assertThat(retrospective.content).isEqualTo("충분히 긴 회고 내용입니다.")
    }

    @Test
    @DisplayName("회고 내용이 10자 미만이면 예외가 발생한다")
    fun `너무 짧은 회고 내용이면 예외 발생`() {
        // expect
        assertThatThrownBy {
            Retrospective(
                studentId = "student-id",
                problemId = "problem-id",
                content = "너무짧다"
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("회고 내용은 10자 이상이어야 합니다.")
    }

    @Test
    @DisplayName("updateContent는 유효한 길이의 내용으로 회고를 수정할 수 있다")
    fun `updateContent로 회고 내용 수정`() {
        // given
        val retrospective = Retrospective(
            studentId = "student-id",
            problemId = "problem-id",
            content = "충분히 긴 회고 내용입니다."
        )

        // when
        val updated = retrospective.updateContent("새로운 회고 내용도 충분히 깁니다.")

        // then
        assertThat(updated.content).isEqualTo("새로운 회고 내용도 충분히 깁니다.")
    }

    @Test
    @DisplayName("updateContent에 10자 미만의 내용이 들어오면 예외가 발생한다")
    fun `updateContent에 너무 짧은 내용 전달시 예외`() {
        // given
        val retrospective = Retrospective(
            studentId = "student-id",
            problemId = "problem-id",
            content = "충분히 긴 회고 내용입니다."
        )

        // expect
        assertThatThrownBy {
            retrospective.updateContent("짧아요")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("회고 내용은 10자 이상이어야 합니다.")
    }
}


