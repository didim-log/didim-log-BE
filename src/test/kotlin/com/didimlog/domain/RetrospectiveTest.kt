package com.didimlog.domain

import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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
        val updated = retrospective.updateContent("새로운 회고 내용도 충분히 깁니다.", "한 줄 요약 테스트")

        // then
        assertThat(updated.content).isEqualTo("새로운 회고 내용도 충분히 깁니다.")
        assertThat(updated.summary).isEqualTo("한 줄 요약 테스트")
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

    @Test
    @DisplayName("isOwner는 회고 소유자일 때 true를 반환한다")
    fun `소유자인 경우 true 반환`() {
        // given
        val ownerId = "owner-123"
        val student = createStudent(id = ownerId)
        val retrospective = createRetrospective(studentId = ownerId)

        // when
        val result = retrospective.isOwner(student)

        // then
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("isOwner는 회고 소유자가 아닐 때 false를 반환한다")
    fun `소유자가 아닌 경우 false 반환`() {
        // given
        val ownerId = "owner-123"
        val otherId = "other-456"
        val otherStudent = createStudent(id = otherId)
        val retrospective = createRetrospective(studentId = ownerId)

        // when
        val result = retrospective.isOwner(otherStudent)

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("isOwner는 Student의 id가 null일 때 false를 반환한다")
    fun `Student id가 null인 경우 false 반환`() {
        // given
        val student = createStudent(id = null)
        val retrospective = createRetrospective(studentId = "owner-123")

        // when
        val result = retrospective.isOwner(student)

        // then
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("validateOwner는 소유자일 때 예외를 발생시키지 않는다")
    fun `소유자 검증 성공`() {
        // given
        val ownerId = "owner-123"
        val student = createStudent(id = ownerId)
        val retrospective = createRetrospective(studentId = ownerId)

        // when & then
        assertThatCode {
            retrospective.validateOwner(student)
        }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("validateOwner는 소유자가 아닐 때 예외를 발생시킨다")
    fun `소유자 검증 실패 시 예외 발생`() {
        // given
        val ownerId = "owner-123"
        val otherId = "other-456"
        val otherStudent = createStudent(id = otherId)
        val retrospective = createRetrospective(studentId = ownerId)

        // when & then
        assertThatThrownBy {
            retrospective.validateOwner(otherStudent)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("회고 소유자가 아닙니다")
    }

    private fun createStudent(id: String?): Student {
        return Student(
            id = id,
            nickname = Nickname("test-user"),
            provider = Provider.BOJ,
            providerId = "testuser",
            bojId = BojId("testuser"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            primaryLanguage = null
        )
    }

    private fun createRetrospective(studentId: String, id: String? = null): Retrospective {
        return Retrospective(
            id = id,
            studentId = studentId,
            problemId = "problem-1",
            content = "충분히 긴 회고 내용입니다."
        )
    }
}



