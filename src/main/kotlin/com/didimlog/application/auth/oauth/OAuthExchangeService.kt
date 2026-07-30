package com.didimlog.application.auth.oauth

import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.security.SecureRandom
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class OAuthExchangeService(
    private val exchangeCodeStore: OAuthExchangeCodeStore,
    private val studentRepository: StudentRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenService: RefreshTokenService,
    @Value("\${app.oauth.exchange-code-ttl-seconds:120}")
    private val exchangeCodeTtlSeconds: Long
) {

    init {
        require(exchangeCodeTtlSeconds in 60..120) {
            "OAuth 교환 코드 TTL은 60초 이상 120초 이하여야 합니다."
        }
    }

    data class ExchangeResult(
        val accessToken: String,
        val refreshToken: String,
        val rating: Int,
        val tier: Tier,
        val tierLevel: Int,
        val provider: Provider
    )

    fun issue(studentId: String): String {
        require(studentId.isNotBlank()) { "studentId는 비어 있을 수 없습니다." }

        repeat(MAX_ISSUE_ATTEMPTS) {
            val code = generateCode()
            if (exchangeCodeStore.save(code, studentId, exchangeCodeTtlSeconds)) {
                return code
            }
        }

        throw IllegalStateException("OAuth 교환 코드를 발급할 수 없습니다.")
    }

    fun exchange(code: String): ExchangeResult {
        if (code.isBlank() || code.length > MAX_CODE_LENGTH) {
            throw invalidExchangeCode()
        }

        val studentId = exchangeCodeStore.consume(code)
            ?: throw invalidExchangeCode()
        val student = studentRepository.findById(studentId)
            .orElseThrow(::invalidExchangeCode)
        val bojId = student.bojId?.value
            ?: throw invalidExchangeCode()
        if (student.role != Role.USER && student.role != Role.ADMIN) {
            throw invalidExchangeCode()
        }

        val accessToken = jwtTokenProvider.createToken(bojId, student.role.value)
        val refreshToken = refreshTokenService.generateAndSave(bojId)

        return ExchangeResult(
            accessToken = accessToken,
            refreshToken = refreshToken,
            rating = student.rating,
            tier = student.tier(),
            tierLevel = student.solvedAcTierLevel.value,
            provider = student.provider
        )
    }

    private fun generateCode(): String {
        val bytes = ByteArray(CODE_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun invalidExchangeCode(): BusinessException {
        return BusinessException(
            ErrorCode.OAUTH_EXCHANGE_CODE_INVALID,
            ErrorCode.OAUTH_EXCHANGE_CODE_INVALID.message
        )
    }

    companion object {
        private const val CODE_BYTES = 32
        private const val MAX_CODE_LENGTH = 128
        private const val MAX_ISSUE_ATTEMPTS = 3
        private val SECURE_RANDOM = SecureRandom()
    }
}
