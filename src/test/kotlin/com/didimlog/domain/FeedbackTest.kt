package com.didimlog.domain

import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Feedback 도메인 테스트")
class FeedbackTest {

    @Test
    @DisplayName("피드백 내용은 비어 있을 수 없고 10자 이상이어야 한다")
    fun `피드백 내용 검증`() {
        assertThatThrownBy { Feedback(writerId = "w", content = "   ", type = FeedbackType.BUG) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("피드백 내용은 필수입니다.")
        assertThatThrownBy { Feedback(writerId = "w", content = "짧은 내용", type = FeedbackType.BUG) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("피드백 내용은 10자 이상이어야 합니다.")
    }

    @Test
    @DisplayName("일반 생성자는 접수 상태와 현재 시각을 기본값으로 사용한다")
    fun `일반 생성자 기본값`() {
        val feedback = Feedback(writerId = "writer-1", content = "버그를 제보합니다 재현 방법", type = FeedbackType.BUG)

        assertThat(feedback.id).isNull()
        assertThat(feedback.status).isEqualTo(FeedbackStatus.PENDING)
        assertThat(feedback.createdAt).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS))
    }

    @Test
    @DisplayName("저장된 문서에 상태나 시각이 없으면 기본값으로 복원한다")
    fun `레거시 문서 복원`() {
        val restored = Feedback(
            writerId = "writer-1",
            content = "건의사항이 있습니다 확인 부탁",
            type = FeedbackType.SUGGESTION,
            status = null,
            createdAt = null,
            updatedAt = null,
            id = "feedback-1"
        )

        assertThat(restored.id).isEqualTo("feedback-1")
        assertThat(restored.status).isEqualTo(FeedbackStatus.PENDING)
        assertThat(restored.createdAt).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS))
        assertThat(restored.updatedAt).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS))
    }

    @Test
    @DisplayName("상태 변경은 상태와 수정 시각만 바꾼다")
    fun `상태 변경`() {
        val createdAt = LocalDateTime.of(2025, 1, 1, 0, 0)
        val feedback = Feedback(
            writerId = "writer-1",
            content = "버그를 제보합니다 재현 방법",
            type = FeedbackType.BUG,
            status = FeedbackStatus.PENDING,
            createdAt = createdAt,
            updatedAt = createdAt,
            id = "feedback-1"
        )

        val completed = feedback.updateStatus(FeedbackStatus.COMPLETED)

        assertThat(completed.status).isEqualTo(FeedbackStatus.COMPLETED)
        assertThat(completed.updatedAt).isAfter(createdAt)
        assertThat(completed.createdAt).isEqualTo(createdAt)
        assertThat(completed.id).isEqualTo("feedback-1")
    }
}
