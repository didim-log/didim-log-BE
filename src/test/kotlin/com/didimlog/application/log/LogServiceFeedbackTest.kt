package com.didimlog.application.log

import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Log
import com.didimlog.domain.Student
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

@DisplayName("LogService 피드백 테스트")
class LogServiceFeedbackTest {

    private val logRepository: LogRepository = mockk()
    private val logFeedbackRepository: LogFeedbackRepository = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val studentLifecycleCoordinator = RecordingStudentLifecycleCoordinator()
    private val logService = LogService(
        logRepository,
        logFeedbackRepository,
        studentRepository,
        studentLifecycleCoordinator
    )

    @Test
    @DisplayName("피드백 업데이트 성공 - LIKE")
    fun `피드백 업데이트 성공 LIKE`() {
        // given
        val logId = "log-123"
        val requesterStudentId = "owner-id"
        val log = log(logId, requesterStudentId, BojId("owner1"))
        val updatedLog = log.copy(aiFeedbackStatus = AiFeedbackStatus.LIKE)

        every { studentRepository.findById(requesterStudentId) } returns Optional.of(mockk<Student>())
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            logFeedbackRepository.updateFeedback(
                logId,
                requesterStudentId,
                AiFeedbackStatus.LIKE,
                null
            )
        } returns updatedLog

        // when
        val result = logService.updateFeedback(logId, requesterStudentId, AiFeedbackStatus.LIKE, null)

        // then
        assertThat(result.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.LIKE)
        assertThat(result.aiFeedbackReason).isNull()
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly(requesterStudentId)
        verify(exactly = 1) { logRepository.findById(logId) }
        verify(exactly = 1) {
            logFeedbackRepository.updateFeedback(
                logId,
                requesterStudentId,
                AiFeedbackStatus.LIKE,
                null
            )
        }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("피드백 업데이트 성공 - DISLIKE with reason")
    fun `피드백 업데이트 성공 DISLIKE with reason`() {
        // given
        val logId = "log-123"
        val requesterStudentId = "owner-id"
        val reason = "INACCURATE"
        val log = log(logId, requesterStudentId, BojId("owner1"))
        val updatedLog = log.copy(
            aiFeedbackStatus = AiFeedbackStatus.DISLIKE,
            aiFeedbackReason = reason
        )

        every { studentRepository.findById(requesterStudentId) } returns Optional.of(mockk<Student>())
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            logFeedbackRepository.updateFeedback(
                logId,
                requesterStudentId,
                AiFeedbackStatus.DISLIKE,
                reason
            )
        } returns updatedLog

        // when
        val result = logService.updateFeedback(logId, requesterStudentId, AiFeedbackStatus.DISLIKE, reason)

        // then
        assertThat(result.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.DISLIKE)
        assertThat(result.aiFeedbackReason).isEqualTo(reason)
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly(requesterStudentId)
        verify(exactly = 1) { logRepository.findById(logId) }
        verify(exactly = 1) {
            logFeedbackRepository.updateFeedback(
                logId,
                requesterStudentId,
                AiFeedbackStatus.DISLIKE,
                reason
            )
        }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("피드백 업데이트 실패 - 로그를 찾을 수 없음")
    fun `피드백 업데이트 실패 로그 없음`() {
        // given
        val logId = "non-existent"

        every { studentRepository.findById("owner1") } returns Optional.of(mockk<Student>())
        every { logRepository.findById(logId) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, "owner1", AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("로그를 찾을 수 없습니다")
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("잠금 안에서 학생이 사라졌으면 로그를 갱신하지 않는다")
    fun `피드백 업데이트 실패 학생 없음`() {
        every { studentRepository.findById("deleted-student") } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(
                logId = "log-123",
                requesterStudentId = "deleted-student",
                status = AiFeedbackStatus.LIKE
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(studentLifecycleCoordinator.executedStudentIds).containsExactly("deleted-student")
        verify(exactly = 0) { logRepository.findById(any()) }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("조건부 갱신 직전에 로그가 삭제되면 다시 생성하지 않는다")
    fun `피드백 부분 갱신 실패 로그 삭제`() {
        val logId = "log-123"
        val studentId = "owner1"
        every { studentRepository.findById(studentId) } returns Optional.of(mockk<Student>())
        every { logRepository.findById(logId) } returns Optional.of(
            log(logId, studentId, BojId("owner1"))
        )
        every {
            logFeedbackRepository.updateFeedback(
                logId,
                studentId,
                AiFeedbackStatus.LIKE,
                null
            )
        } returns null

        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, studentId, AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("인증 학생 ID가 비어 있으면 로그를 조회하지 않는다")
    fun `피드백 업데이트 실패 인증 정보 없음`() {
        val exception = assertThrows<BusinessException> {
            logService.updateFeedback("log-123", " ", AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        verify(exactly = 0) { logRepository.findById(any()) }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("소유자가 없는 로그에는 피드백을 저장하지 않는다")
    fun `피드백 업데이트 실패 로그 소유자 없음`() {
        val logId = "log-123"
        every { studentRepository.findById("owner1") } returns Optional.of(mockk<Student>())
        every { logRepository.findById(logId) } returns Optional.of(log(logId, null, BojId("owner1")))

        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, "owner1", AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("다른 사용자의 로그에는 피드백을 저장하지 않는다")
    fun `피드백 업데이트 실패 소유자 불일치`() {
        val logId = "log-123"
        every { studentRepository.findById("other1") } returns Optional.of(mockk<Student>())
        every {
            logRepository.findById(logId)
        } returns Optional.of(log(logId, "owner-id", BojId("owner1")))

        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, "other1", AiFeedbackStatus.DISLIKE, "INACCURATE")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
        verify(exactly = 0) { logRepository.save(any()) }
    }

    private fun log(logId: String, studentId: String?, bojId: BojId?): Log {
        return Log(
            id = logId,
            title = LogTitle("Test"),
            content = LogContent("Content"),
            code = LogCode("code"),
            studentId = studentId,
            bojId = bojId,
            aiFeedbackStatus = AiFeedbackStatus.NONE
        )
    }

    private class RecordingStudentLifecycleCoordinator : StudentLifecycleCoordinator {
        val executedStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T {
            executedStudentIds += studentId
            return action()
        }
    }
}







