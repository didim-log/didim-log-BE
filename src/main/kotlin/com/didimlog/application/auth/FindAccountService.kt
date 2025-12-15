package com.didimlog.application.auth

import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 계정 찾기 서비스
 * OAuth-only 환경에서 이메일로 가입된 소셜 제공자를 안내한다.
 */
@Service
class FindAccountService(
    private val studentRepository: StudentRepository
) {

    data class FindAccountResult(
        val provider: String,
        val message: String
    )

    @Transactional(readOnly = true)
    fun findAccount(email: String): FindAccountResult {
        val normalizedEmail = email.trim()

        val student = studentRepository.findByEmail(normalizedEmail)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "가입 정보가 없습니다.")
            }

        val provider = student.provider.name
        return FindAccountResult(
            provider = provider,
            message = "해당 이메일은 ${provider}로 가입되었습니다."
        )
    }
}

