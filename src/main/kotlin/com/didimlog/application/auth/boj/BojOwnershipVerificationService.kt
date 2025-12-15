package com.didimlog.application.auth.boj

import com.didimlog.domain.enums.Role
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.security.SecureRandom
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BojOwnershipVerificationService(
    private val codeStore: BojVerificationCodeStore,
    private val statusMessageClient: BojProfileStatusMessageClient,
    private val studentRepository: StudentRepository
) {

    companion object {
        private const val CODE_PREFIX = "DIDIM-LOG-"
        private const val CODE_LENGTH = 4
        private const val DEFAULT_TTL_SECONDS = 5 * 60L
    }

    data class IssuedCode(
        val sessionId: String,
        val code: String,
        val expiresInSeconds: Long
    )

    @Transactional(readOnly = true)
    fun issueVerificationCode(): IssuedCode {
        val sessionId = UUID.randomUUID().toString()
        val code = CODE_PREFIX + randomUpperAlphaNumeric(CODE_LENGTH)
        codeStore.save(sessionId, code, DEFAULT_TTL_SECONDS)
        return IssuedCode(sessionId = sessionId, code = code, expiresInSeconds = DEFAULT_TTL_SECONDS)
    }

    @Transactional
    fun verifyOwnership(sessionId: String, bojId: String) {
        val storedCode = codeStore.find(sessionId)
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "인증 코드가 만료되었거나 존재하지 않습니다.")

        val bojIdVo = BojId(bojId)
        val statusMessage = statusMessageClient.fetchStatusMessage(bojIdVo.value).orEmpty()

        if (!statusMessage.contains(storedCode)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "상태 메시지에서 코드를 찾을 수 없습니다.")
        }

        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow { BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. bojId=$bojId") }

        val verifiedStudent = student.copy(isVerified = true, role = Role.USER, bojId = student.bojId ?: bojIdVo)
        studentRepository.save(verifiedStudent)

        codeStore.delete(sessionId)
    }

    private fun randomUpperAlphaNumeric(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(chars[random.nextInt(chars.length)])
        }
        return builder.toString()
    }
}

