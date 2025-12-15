package com.didimlog.application.feedback

import com.didimlog.domain.Feedback
import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("FeedbackService 테스트")
class FeedbackServiceTest {

    private val feedbackRepository: FeedbackRepository = mockk()
    private val feedbackService = FeedbackService(feedbackRepository)

    @Test
    @DisplayName("피드백을 등록할 수 있다")
    fun `피드백 등록 성공`() {
        // given
        val writerId = "student1"
        val content = "버그 리포트입니다. 자세한 내용은..."
        val type = FeedbackType.BUG
        val savedFeedback = Feedback(
            writerId = writerId,
            content = content,
            type = type,
            status = FeedbackStatus.PENDING
        ).copy(id = "feedback1")

        every { feedbackRepository.save(any<Feedback>()) } returns savedFeedback

        // when
        val result = feedbackService.createFeedback(writerId, content, type)

        // then
        assertThat(result.writerId).isEqualTo(writerId)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.type).isEqualTo(type)
        assertThat(result.status).isEqualTo(FeedbackStatus.PENDING)
        verify(exactly = 1) { feedbackRepository.save(any<Feedback>()) }
    }

    @Test
    @DisplayName("피드백 목록을 페이징하여 조회할 수 있다")
    fun `피드백 목록 조회 성공`() {
        // given
        val feedbacks = listOf(
            Feedback(
                writerId = "student1",
                content = "버그 리포트 1입니다. 매우 긴 내용을 작성합니다.",
                type = FeedbackType.BUG,
                status = FeedbackStatus.PENDING
            ).copy(id = "feedback1"),
            Feedback(
                writerId = "student2",
                content = "건의사항 1입니다. 매우 긴 내용을 작성합니다.",
                type = FeedbackType.SUGGESTION,
                status = FeedbackStatus.COMPLETED
            ).copy(id = "feedback2")
        )
        val pageable = PageRequest.of(0, 20)
        val page = PageImpl(feedbacks, pageable, feedbacks.size.toLong())

        every { feedbackRepository.findAll(pageable) } returns page

        // when
        val result = feedbackService.getAllFeedbacks(pageable)

        // then
        assertThat(result.content).hasSize(2)
        assertThat(result.content[0].type).isEqualTo(FeedbackType.BUG)
        assertThat(result.content[1].type).isEqualTo(FeedbackType.SUGGESTION)
        verify(exactly = 1) { feedbackRepository.findAll(pageable) }
    }

    @Test
    @DisplayName("피드백 상태를 변경할 수 있다")
    fun `피드백 상태 변경 성공`() {
        // given
        val feedbackId = "feedback1"
        val existingFeedback = Feedback(
            writerId = "student1",
            content = "버그 리포트입니다. 매우 긴 내용을 작성합니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.PENDING
        ).copy(id = feedbackId)
        val newStatus = FeedbackStatus.COMPLETED

        every { feedbackRepository.findById(feedbackId) } returns Optional.of(existingFeedback)
        every { feedbackRepository.save(any<Feedback>()) } answers { firstArg() }

        // when
        val result = feedbackService.updateFeedbackStatus(feedbackId, newStatus)

        // then
        assertThat(result.status).isEqualTo(newStatus)
        verify(exactly = 1) { feedbackRepository.findById(feedbackId) }
        verify(exactly = 1) { feedbackRepository.save(any<Feedback>()) }
    }

    @Test
    @DisplayName("존재하지 않는 피드백 상태 변경 시 예외가 발생한다")
    fun `존재하지 않는 피드백 상태 변경 시 예외 발생`() {
        // given
        val feedbackId = "non-existent"
        val newStatus = FeedbackStatus.COMPLETED

        every { feedbackRepository.findById(feedbackId) } returns Optional.empty()

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            feedbackService.updateFeedbackStatus(feedbackId, newStatus)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("피드백을 찾을 수 없습니다")
    }
}


