package com.didimlog.application.student

import com.didimlog.domain.Student
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
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
    private val passwordEncoder: PasswordEncoder
) {

    /**
     * 학생의 프로필 정보를 수정한다.
     * 닉네임과 비밀번호를 선택적으로 변경할 수 있다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @param nickname 변경할 닉네임 (null이면 변경하지 않음)
     * @param currentPassword 현재 비밀번호 (비밀번호 변경 시 필수)
     * @param newPassword 새로운 비밀번호 (null이면 변경하지 않음)
     * @return 수정된 Student 엔티티
     * @throws BusinessException 닉네임 중복, 비밀번호 불일치, 비밀번호 정책 위반 시
     */
    @Transactional
    fun updateProfile(
        bojId: String,
        nickname: String?,
        currentPassword: String?,
        newPassword: String?
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

            // 새 비밀번호 복잡도 검증
            PasswordValidator.validate(newPassword)

            // 새 비밀번호 암호화
            val encodedNewPassword = passwordEncoder.encode(newPassword)
            updatedStudent = updatedStudent.copy(password = encodedNewPassword)
        }

        return studentRepository.save(updatedStudent)
    }
}
