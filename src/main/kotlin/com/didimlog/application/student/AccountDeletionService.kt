package com.didimlog.application.student

import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service

/**
 * 본인 탈퇴와 관리자 강제 탈퇴가 공유하는 계정 삭제 흐름이다.
 *
 * MongoDB 여러 컬렉션과 Redis를 함께 다루므로 전체 과정이 하나의 트랜잭션은 아니다.
 * 중간 실패 뒤 같은 학생 ID로 다시 정리할 수 있도록 Student 문서를 마지막에 삭제한다.
 */
@Service
class AccountDeletionService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository,
    private val feedbackRepository: FeedbackRepository,
    private val logRepository: LogRepository,
    private val templateRepository: TemplateRepository,
    private val passwordResetCodeRepository: PasswordResetCodeRepository,
    private val refreshTokenService: RefreshTokenService,
    private val studentLifecycleCoordinator: StudentLifecycleCoordinator
) {

    private val log = LoggerFactory.getLogger(AccountDeletionService::class.java)

    fun deleteAccount(studentId: String) {
        studentLifecycleCoordinator.execute(studentId) {
            val student = studentRepository.findById(studentId)
                .orElseThrow {
                    BusinessException(
                        ErrorCode.STUDENT_NOT_FOUND,
                        "학생을 찾을 수 없습니다. studentId=$studentId"
                    )
                }
            log.warn(
                "회원 탈퇴 처리 시작(Hard Delete). 복구 불가. studentId={}",
                studentId
            )

            // 세션 정리에 실패하면 MongoDB 삭제를 시작하지 않는다.
            try {
                refreshTokenService.revokeAllForStudent(studentId)
            } catch (exception: DataAccessException) {
                throw BusinessException(ErrorCode.SESSION_STATE_UNAVAILABLE).also {
                    it.initCause(exception)
                }
            }

            passwordResetCodeRepository.deleteAllByStudentId(studentId)
            retrospectiveRepository.deleteAllByStudentId(studentId)
            feedbackRepository.deleteAllByWriterId(studentId)
            clearDefaultTemplateReferences(student)
            templateRepository.deleteAllByStudentId(studentId)
            logRepository.deleteAllByStudentId(studentId)

            // 연관 데이터 정리가 끝난 뒤 안정적인 ID로 학생 문서를 제거한다.
            studentRepository.deleteById(studentId)
        }
    }

    private fun clearDefaultTemplateReferences(student: Student) {
        val studentId = requireNotNull(student.id)
        val templateIds = setOfNotNull(
            student.defaultSuccessTemplateId,
            student.defaultFailTemplateId
        )
        templateIds
            .filterNot { templateId ->
                templateRepository.existsByIdAndType(templateId, TemplateOwnershipType.SYSTEM)
            }
            .forEach { templateId ->
                val categories = student.defaultTemplateCategories(templateId)
                studentRepository.clearDefaultTemplateReferences(
                    studentId = studentId,
                    expectedTemplateId = templateId,
                    categories = categories
                ) ?: throw BusinessException(
                    ErrorCode.SESSION_STATE_CONFLICT,
                    "기본 템플릿 참조가 변경되어 계정 삭제를 완료하지 못했습니다."
                )
            }
    }
}
