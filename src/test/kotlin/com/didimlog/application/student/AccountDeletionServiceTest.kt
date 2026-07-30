package com.didimlog.application.student

import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.RedisConnectionFailureException

@DisplayName("AccountDeletionService 테스트")
class AccountDeletionServiceTest {

    private val studentRepository = mockk<StudentRepository>(relaxed = true)
    private val retrospectiveRepository = mockk<RetrospectiveRepository>(relaxed = true)
    private val feedbackRepository = mockk<FeedbackRepository>(relaxed = true)
    private val logRepository = mockk<LogRepository>(relaxed = true)
    private val templateRepository = mockk<TemplateRepository>(relaxed = true)
    private val passwordResetCodeRepository = mockk<PasswordResetCodeRepository>(relaxed = true)
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)

    private val accountDeletionService = AccountDeletionService(
        studentRepository = studentRepository,
        retrospectiveRepository = retrospectiveRepository,
        feedbackRepository = feedbackRepository,
        logRepository = logRepository,
        templateRepository = templateRepository,
        passwordResetCodeRepository = passwordResetCodeRepository,
        refreshTokenService = refreshTokenService,
        studentLifecycleCoordinator = ImmediateCredentialSessionCoordinator()
    )

    @Test
    @DisplayName("세션과 모든 연관 데이터를 정해진 순서로 삭제한다")
    fun `계정과 연관 데이터 삭제 순서`() {
        val studentId = "student-id"
        val bojId = "deleteboj"
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId, bojId))

        accountDeletionService.deleteAccount(studentId)

        verifyOrder {
            studentRepository.findById(studentId)
            refreshTokenService.revokeAllForStudent(studentId)
            passwordResetCodeRepository.deleteAllByStudentId(studentId)
            retrospectiveRepository.deleteAllByStudentId(studentId)
            feedbackRepository.deleteAllByWriterId(studentId)
            templateRepository.deleteAllByStudentId(studentId)
            logRepository.deleteAllByStudentId(studentId)
            studentRepository.deleteById(studentId)
        }
    }

    @Test
    @DisplayName("학생이 없으면 세션과 연관 데이터를 삭제하지 않는다")
    fun `학생 없음이면 삭제하지 않음`() {
        val studentId = "missing-student"
        every { studentRepository.findById(studentId) } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            accountDeletionService.deleteAccount(studentId)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 0) {
            refreshTokenService.revokeAllForStudent(any())
            passwordResetCodeRepository.deleteAllByStudentId(any())
            retrospectiveRepository.deleteAllByStudentId(any())
            feedbackRepository.deleteAllByWriterId(any())
            templateRepository.deleteAllByStudentId(any())
            logRepository.deleteAllByStudentId(any())
            studentRepository.deleteById(any())
        }
    }

    @Test
    @DisplayName("세션 저장소 연결에 실패하면 503으로 변환하고 MongoDB 삭제를 시작하지 않는다")
    fun `세션 저장소 연결 실패이면 503 반환 후 MongoDB 삭제하지 않음`() {
        val studentId = "student-id"
        val failure = RedisConnectionFailureException("Redis unavailable")
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))
        every { refreshTokenService.revokeAllForStudent(studentId) } throws failure

        val exception = assertThrows<BusinessException> {
            accountDeletionService.deleteAccount(studentId)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_UNAVAILABLE)
        assertThat(exception.cause).isSameAs(failure)
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
        verify(exactly = 0) {
            passwordResetCodeRepository.deleteAllByStudentId(any())
            retrospectiveRepository.deleteAllByStudentId(any())
            feedbackRepository.deleteAllByWriterId(any())
            templateRepository.deleteAllByStudentId(any())
            logRepository.deleteAllByStudentId(any())
            studentRepository.deleteById(any())
        }
    }

    @Test
    @DisplayName("연관 데이터 삭제가 중간에 실패하면 학생을 유지하고 재시도로 완료한다")
    fun `중간 삭제 실패 뒤 재시도 성공`() {
        val studentId = "student-id"
        val bojId = "deleteboj"
        var templateDeletionAttempts = 0
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId, bojId))
        every { templateRepository.deleteAllByStudentId(studentId) } answers {
            templateDeletionAttempts += 1
            if (templateDeletionAttempts == 1) {
                throw IllegalStateException("Template deletion failed")
            }
        }

        assertThrows<IllegalStateException> {
            accountDeletionService.deleteAccount(studentId)
        }

        verify(exactly = 0) {
            logRepository.deleteAllByStudentId(any())
            studentRepository.deleteById(any())
        }

        accountDeletionService.deleteAccount(studentId)

        verify(exactly = 2) {
            studentRepository.findById(studentId)
            refreshTokenService.revokeAllForStudent(studentId)
            passwordResetCodeRepository.deleteAllByStudentId(studentId)
            retrospectiveRepository.deleteAllByStudentId(studentId)
            feedbackRepository.deleteAllByWriterId(studentId)
            templateRepository.deleteAllByStudentId(studentId)
        }
        verify(exactly = 1) {
            logRepository.deleteAllByStudentId(studentId)
            studentRepository.deleteById(studentId)
        }
    }

    @Test
    @DisplayName("템플릿 삭제 뒤 후속 단계가 실패해도 Student 기본값 참조는 먼저 해제한다")
    fun `계정 삭제 중간 실패 전에 기본 템플릿 참조 해제`() {
        val studentId = "student-id"
        val templateId = "default-template"
        val target = student(studentId).copy(
            defaultSuccessTemplateId = templateId,
            defaultFailTemplateId = templateId
        )
        val categories = setOf(TemplateCategory.SUCCESS, TemplateCategory.FAIL)
        every { studentRepository.findById(studentId) } returns Optional.of(target)
        every {
            studentRepository.clearDefaultTemplateReferences(studentId, templateId, categories)
        } returns target.copy(
            defaultSuccessTemplateId = null,
            defaultFailTemplateId = null
        )
        every { logRepository.deleteAllByStudentId(studentId) } throws
            IllegalStateException("Log deletion failed")

        assertThrows<IllegalStateException> {
            accountDeletionService.deleteAccount(studentId)
        }

        verifyOrder {
            studentRepository.clearDefaultTemplateReferences(studentId, templateId, categories)
            templateRepository.deleteAllByStudentId(studentId)
            logRepository.deleteAllByStudentId(studentId)
        }
        verify(exactly = 0) { studentRepository.deleteById(studentId) }
    }

    @Test
    @DisplayName("기본 템플릿 참조가 달라지면 사용자 템플릿 삭제를 시작하지 않는다")
    fun `계정 삭제 기본 템플릿 참조 변경 충돌`() {
        val studentId = "student-id"
        val templateId = "default-template"
        val target = student(studentId).copy(defaultSuccessTemplateId = templateId)
        val categories = setOf(TemplateCategory.SUCCESS)
        every { studentRepository.findById(studentId) } returns Optional.of(target)
        every {
            studentRepository.clearDefaultTemplateReferences(studentId, templateId, categories)
        } returns null

        val exception = assertThrows<BusinessException> {
            accountDeletionService.deleteAccount(studentId)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        verify(exactly = 0) {
            templateRepository.deleteAllByStudentId(any())
            logRepository.deleteAllByStudentId(any())
            studentRepository.deleteById(any())
        }
    }

    @Test
    @DisplayName("계정 삭제가 중간 실패해도 시스템 템플릿 기본값은 해제하지 않는다")
    fun `계정 삭제 중 시스템 기본 템플릿 참조 보존`() {
        val studentId = "student-id"
        val systemTemplateId = "system-template"
        val target = student(studentId).copy(defaultSuccessTemplateId = systemTemplateId)
        every { studentRepository.findById(studentId) } returns Optional.of(target)
        every {
            templateRepository.existsByIdAndType(
                systemTemplateId,
                TemplateOwnershipType.SYSTEM
            )
        } returns true
        every { logRepository.deleteAllByStudentId(studentId) } throws
            IllegalStateException("Log deletion failed")

        assertThrows<IllegalStateException> {
            accountDeletionService.deleteAccount(studentId)
        }

        verify(exactly = 0) {
            studentRepository.clearDefaultTemplateReferences(any(), any(), any())
        }
        verify(exactly = 1) {
            templateRepository.existsByIdAndType(
                systemTemplateId,
                TemplateOwnershipType.SYSTEM
            )
        }
    }

    private fun student(
        studentId: String,
        bojId: String = "deleteboj"
    ): Student {
        return Student(
            id = studentId,
            nickname = Nickname("deleteuser"),
            provider = Provider.BOJ,
            providerId = "delete-provider",
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }
}
