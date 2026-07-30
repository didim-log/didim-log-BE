package com.didimlog.application.feedback

import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Feedback
import com.didimlog.domain.Student
import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.util.*

@DisplayName("FeedbackService 테스트")
class FeedbackServiceTest {

    private val feedbackRepository: FeedbackRepository = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val studentLifecycleCoordinator = RecordingStudentLifecycleCoordinator()
    private val feedbackService = FeedbackService(
        feedbackRepository,
        studentRepository,
        studentLifecycleCoordinator
    )

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

        every { studentRepository.findById(writerId) } returns Optional.of(mockk<Student>())
        every { feedbackRepository.save(any<Feedback>()) } returns savedFeedback

        // when
        val result = feedbackService.createFeedback(writerId, content, type)

        // then
        assertThat(result.writerId).isEqualTo(writerId)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.type).isEqualTo(type)
        assertThat(result.status).isEqualTo(FeedbackStatus.PENDING)
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly(writerId)
        verify(exactly = 1) { feedbackRepository.save(any<Feedback>()) }
    }

    @Test
    @DisplayName("삭제된 학생은 피드백을 등록할 수 없다")
    fun `피드백 등록 실패 학생 없음`() {
        every { studentRepository.findById("deleted-student") } returns Optional.empty()

        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            feedbackService.createFeedback(
                "deleted-student",
                "삭제된 학생이 작성하려는 피드백입니다.",
                FeedbackType.BUG
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly("deleted-student")
        verify(exactly = 0) { feedbackRepository.save(any()) }
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

        every {
            studentRepository.findById(existingFeedback.writerId)
        } returns Optional.of(mockk<Student>())
        every { feedbackRepository.findById(feedbackId) } returns Optional.of(existingFeedback)
        every { feedbackRepository.save(any<Feedback>()) } answers { firstArg() }

        // when
        val result = feedbackService.updateFeedbackStatus(feedbackId, newStatus)

        // then
        assertThat(result.status).isEqualTo(newStatus)
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly(existingFeedback.writerId)
        verify(exactly = 2) { feedbackRepository.findById(feedbackId) }
        verify(exactly = 1) { feedbackRepository.save(any<Feedback>()) }
    }

    @Test
    @DisplayName("잠금 안에서 피드백이 사라지면 상태를 다시 저장하지 않는다")
    fun `피드백 상태 변경 실패 잠금 안에서 삭제됨`() {
        val feedbackId = "feedback1"
        val existingFeedback = Feedback(
            writerId = "student1",
            content = "삭제와 경합하는 피드백 상태 변경입니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.PENDING
        ).copy(id = feedbackId)
        every {
            studentRepository.findById(existingFeedback.writerId)
        } returns Optional.of(mockk<Student>())
        every {
            feedbackRepository.findById(feedbackId)
        } returnsMany listOf(Optional.of(existingFeedback), Optional.empty())

        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            feedbackService.updateFeedbackStatus(feedbackId, FeedbackStatus.COMPLETED)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        verify(exactly = 0) { feedbackRepository.save(any()) }
    }

    @Test
    @DisplayName("잠금 안에서 학생이 사라지면 피드백 상태를 저장하지 않는다")
    fun `피드백 상태 변경 실패 학생 없음`() {
        val feedbackId = "feedback1"
        val existingFeedback = Feedback(
            writerId = "deleted-student",
            content = "탈퇴와 경합하는 피드백 상태 변경입니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.PENDING
        ).copy(id = feedbackId)
        every { feedbackRepository.findById(feedbackId) } returns Optional.of(existingFeedback)
        every { studentRepository.findById(existingFeedback.writerId) } returns Optional.empty()

        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            feedbackService.updateFeedbackStatus(feedbackId, FeedbackStatus.COMPLETED)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly(existingFeedback.writerId)
        verify(exactly = 1) { feedbackRepository.findById(feedbackId) }
        verify(exactly = 0) { feedbackRepository.save(any()) }
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

    @Test
    @DisplayName("완료된 피드백을 삭제할 수 있다")
    fun `완료된 피드백 삭제 성공`() {
        // given
        val feedbackId = "feedback1"
        val completedFeedback = Feedback(
            writerId = "student1",
            content = "버그 리포트입니다. 매우 긴 내용을 작성합니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.COMPLETED
        ).copy(id = feedbackId)

        every {
            studentRepository.findById(completedFeedback.writerId)
        } returns Optional.of(mockk<Student>())
        every { feedbackRepository.findById(feedbackId) } returns Optional.of(completedFeedback)
        every { feedbackRepository.delete(completedFeedback) } returns Unit

        // when
        feedbackService.deleteFeedback(feedbackId)

        // then
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly(completedFeedback.writerId)
        verify(exactly = 2) { feedbackRepository.findById(feedbackId) }
        verify(exactly = 1) { feedbackRepository.delete(completedFeedback) }
    }

    @Test
    @DisplayName("완료되지 않은 피드백 삭제 시 예외가 발생한다")
    fun `완료되지 않은 피드백 삭제 시 예외 발생`() {
        // given
        val feedbackId = "feedback1"
        val pendingFeedback = Feedback(
            writerId = "student1",
            content = "버그 리포트입니다. 매우 긴 내용을 작성합니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.PENDING
        ).copy(id = feedbackId)

        every {
            studentRepository.findById(pendingFeedback.writerId)
        } returns Optional.of(mockk<Student>())
        every { feedbackRepository.findById(feedbackId) } returns Optional.of(pendingFeedback)

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            feedbackService.deleteFeedback(feedbackId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("완료된 피드백만 삭제할 수 있습니다")
        verify(exactly = 2) { feedbackRepository.findById(feedbackId) }
        verify(exactly = 0) { feedbackRepository.delete(any()) }
    }

    @Test
    @DisplayName("존재하지 않는 피드백 삭제 시 예외가 발생한다")
    fun `존재하지 않는 피드백 삭제 시 예외 발생`() {
        // given
        val feedbackId = "non-existent"

        every { feedbackRepository.findById(feedbackId) } returns Optional.empty()

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            feedbackService.deleteFeedback(feedbackId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("피드백을 찾을 수 없습니다")
        verify(exactly = 1) { feedbackRepository.findById(feedbackId) }
        verify(exactly = 0) { feedbackRepository.delete(any()) }
    }

    private class RecordingStudentLifecycleCoordinator : StudentLifecycleCoordinator {
        val executedStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T {
            executedStudentIds += studentId
            return action()
        }
    }
}




