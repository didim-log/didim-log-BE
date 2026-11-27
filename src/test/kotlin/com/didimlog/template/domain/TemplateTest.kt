package com.didimlog.template.domain

import com.didimlog.template.domain.TemplateOwnershipType
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Template 도메인 테스트")
class TemplateTest {

    private val past = LocalDateTime.of(2025, 1, 1, 0, 0)

    private fun custom(studentId: String? = "student-1") = Template(
        id = "template-1",
        studentId = studentId,
        title = "내 템플릿",
        content = "## 회고",
        type = TemplateOwnershipType.CUSTOM,
        createdAt = past,
        updatedAt = past
    )

    private fun system() = Template(
        title = "시스템 템플릿",
        content = "## 기본",
        type = TemplateOwnershipType.SYSTEM
    )

    @Test
    @DisplayName("제목과 내용의 필수 여부와 길이를 검증한다")
    fun `생성 시 제목과 내용 검증`() {
        assertThatThrownBy { custom().copy(title = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("템플릿 제목은 필수입니다.")
        assertThatThrownBy { custom().copy(title = "t".repeat(101)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("템플릿 제목은 100자 이하여야 합니다.")
        assertThatThrownBy { custom().copy(content = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("템플릿 내용은 필수입니다.")
        assertThatThrownBy { custom().copy(content = "c".repeat(10001)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("템플릿 내용은 10000자 이하여야 합니다.")
        assertThat(custom().copy(title = "t".repeat(100), content = "c".repeat(10000)).title).hasSize(100)
    }

    @Test
    @DisplayName("시스템 템플릿은 소유자를 가질 수 없고 커스텀 템플릿은 소유자가 필요하다")
    fun `소유권 타입별 소유자 검증`() {
        assertThatThrownBy { system().copy(studentId = "student-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("시스템 템플릿은 소유자를 가질 수 없습니다.")
        assertThatThrownBy { custom(studentId = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("커스텀 템플릿은 소유자가 필요합니다.")
    }

    @Test
    @DisplayName("커스텀 템플릿 수정은 제목, 내용, 수정 시각을 갱신한다")
    fun `커스텀 템플릿 수정`() {
        val updated = custom().update("새 제목", "## 새 내용")

        assertThat(updated.title).isEqualTo("새 제목")
        assertThat(updated.content).isEqualTo("## 새 내용")
        assertThat(updated.updatedAt).isAfter(past)
        assertThat(updated.createdAt).isEqualTo(past)
    }

    @Test
    @DisplayName("템플릿 수정은 시스템 템플릿과 잘못된 입력을 거부한다")
    fun `템플릿 수정 거부`() {
        assertThatThrownBy { system().update("제목", "내용") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("시스템 템플릿은 수정할 수 없습니다.")
        assertThatThrownBy { custom().update(" ", "내용") }
            .hasMessage("템플릿 제목은 필수입니다.")
        assertThatThrownBy { custom().update("t".repeat(101), "내용") }
            .hasMessage("템플릿 제목은 100자 이하여야 합니다.")
        assertThatThrownBy { custom().update("제목", " ") }
            .hasMessage("템플릿 내용은 필수입니다.")
        assertThatThrownBy { custom().update("제목", "c".repeat(10001)) }
            .hasMessage("템플릿 내용은 10000자 이하여야 합니다.")
    }

    @Test
    @DisplayName("소유자 확인은 커스텀 템플릿의 학생 ID만 인정하고 시스템 템플릿은 누구의 것도 아니다")
    fun `소유자 확인`() {
        assertThat(custom().isOwner("student-1")).isTrue()
        assertThat(custom().isOwner("student-2")).isFalse()
        assertThat(system().isOwner("student-1")).isFalse()
    }
}
