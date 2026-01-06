package com.didimlog.application.student

import com.didimlog.domain.Student
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
import com.didimlog.infra.solvedac.SolvedAcClient
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 학생 프로필 관리 서비스
 * 닉네임 및 비밀번호 변경 기능을 제공한다.
 */
@Service
class StudentService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository,
    private val feedbackRepository: FeedbackRepository,
    private val passwordEncoder: PasswordEncoder,
    private val solvedAcClient: SolvedAcClient
) {

    private val log = LoggerFactory.getLogger(StudentService::class.java)

    /**
     * 학생의 프로필 정보를 수정한다.
     * 닉네임, 비밀번호, 주 언어를 선택적으로 변경할 수 있다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @param nickname 변경할 닉네임 (null이면 변경하지 않음)
     * @param currentPassword 현재 비밀번호 (비밀번호 변경 시 필수)
     * @param newPassword 새로운 비밀번호 (null이면 변경하지 않음)
     * @param primaryLanguage 변경할 주 언어 (null이면 변경하지 않음)
     * @return 수정된 Student 엔티티
     * @throws BusinessException 닉네임 중복, 비밀번호 불일치, 비밀번호 정책 위반 시
     */
    @Transactional
    fun updateProfile(
        bojId: String,
        nickname: String?,
        currentPassword: String?,
        newPassword: String?,
        primaryLanguage: com.didimlog.domain.enums.PrimaryLanguage? = null
    ): Student {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }

        var updatedStudent = student

        // 닉네임 변경 처리
        if (nickname != null && nickname.isNotBlank()) {
            val newNickname = Nickname(nickname)
            if (student.nickname.value != newNickname.value) {
                // 닉네임 중복 확인
                if (studentRepository.existsByNickname(newNickname)) {
                    throw BusinessException(
                        ErrorCode.DUPLICATE_NICKNAME,
                        "이미 사용 중인 닉네임입니다. nickname=$nickname"
                    )
                }
                updatedStudent = updatedStudent.copy(nickname = newNickname)
            }
        }

        // 비밀번호 변경 처리
        if (newPassword != null && newPassword.isNotBlank()) {
            if (currentPassword == null || currentPassword.isBlank()) {
                throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "비밀번호를 변경하려면 현재 비밀번호를 입력해야 합니다."
                )
            }

            // 현재 비밀번호 검증
            if (!student.matchPassword(currentPassword, passwordEncoder)) {
                throw BusinessException(
                    ErrorCode.PASSWORD_MISMATCH,
                    "현재 비밀번호가 일치하지 않습니다."
                )
            }

            // 새 비밀번호가 현재 비밀번호와 같은지 검증
            if (student.matchPassword(newPassword, passwordEncoder)) {
                throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "새 비밀번호는 현재 비밀번호와 다르게 설정해야 합니다."
                )
            }

            // 새 비밀번호 복잡도 검증
            PasswordValidator.validate(newPassword)

            // 새 비밀번호 암호화
            val encodedNewPassword = passwordEncoder.encode(newPassword)
            updatedStudent = updatedStudent.copy(password = encodedNewPassword)
        }

        // 주 언어 변경 처리
        if (primaryLanguage != null) {
            updatedStudent = updatedStudent.updatePrimaryLanguage(primaryLanguage)
        }

        return studentRepository.save(updatedStudent)
    }

    /**
     * 회원 탈퇴(본인)를 처리한다. (Hard Delete)
     * - Student 및 연관 데이터(Retrospective, Feedback)를 DB에서 완전히 삭제한다.
     *
     * ⚠️ 이 작업은 복구할 수 없다.
     */
    @Transactional
    fun withdraw(bojId: String) {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }

        val studentId = student.id
            ?: throw BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "학생 ID를 찾을 수 없습니다.")

        log.warn("회원 탈퇴 처리 시작(Hard Delete). 복구 불가. bojId={}, studentId={}", bojId, studentId)

        // 연관 데이터 삭제 (studentId 기반)
        retrospectiveRepository.deleteAllByStudentId(studentId)
        feedbackRepository.deleteAllByWriterId(studentId)

        // 본인 데이터 삭제
        studentRepository.delete(student)
    }

    /**
     * BOJ 프로필 정보를 Solved.ac API에서 동기화한다.
     * Rating과 Tier 정보를 최신 상태로 업데이트한다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @return 동기화된 Student 엔티티
     * @throws BusinessException 학생을 찾을 수 없거나, BOJ ID가 없는 경우
     */
    @Transactional
    fun syncBojProfile(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }

        if (student.bojId == null) {
            throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "BOJ 인증이 완료되지 않은 사용자입니다. BOJ 계정을 연동해주세요."
            )
        }

        log.info("BOJ 프로필 동기화 시작: bojId=${bojIdVo.value}")

        try {
            val userResponse = solvedAcClient.fetchUser(bojIdVo)
            val newRating = userResponse.rating
            val newTierLevel = SolvedAcTierLevel(userResponse.tier)

            if (student.rating == newRating && student.solvedAcTierLevel == newTierLevel) {
                log.debug(
                    "BOJ 프로필 동기화 완료 (변경 없음): bojId={}, rating={}, tierLevel={}",
                    bojIdVo.value,
                    newRating,
                    newTierLevel.value
                )
                return student
            }

            val updatedStudent = student.updateSolvedAcProfile(newRating, newTierLevel)
            val savedStudent = studentRepository.save(updatedStudent)

            log.info(
                "BOJ 프로필 동기화 완료: bojId={}, oldRating={}, newRating={}, oldTierLevel={}, newTierLevel={}",
                bojIdVo.value,
                student.rating,
                newRating,
                student.solvedAcTierLevel.value,
                newTierLevel.value
            )
            return savedStudent
        } catch (e: BusinessException) {
            log.error("BOJ 프로필 동기화 실패: bojId=${bojIdVo.value}, error=${e.message}", e)
            throw e
        } catch (e: Exception) {
            log.error("BOJ 프로필 동기화 중 예상치 못한 예외 발생: bojId=${bojIdVo.value}", e)
            throw BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "BOJ 프로필 동기화에 실패했습니다. bojId=${bojIdVo.value}"
            )
        }
    }
}
