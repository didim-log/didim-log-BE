package com.didimlog.application.auth.boj

import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.UUID

@Service
class BojOwnershipVerificationService(
    private val codeStore: BojVerificationCodeStore,
    private val profileStatusMessageClient: BojProfileStatusMessageClient
) {

    private val log = LoggerFactory.getLogger(BojOwnershipVerificationService::class.java)
    private val codeMatcher = BojVerificationCodeMatcher()

    companion object {
        private const val CODE_PREFIX = "DIDIM-LOG-"
        private const val CODE_LENGTH = 6
        private const val DEFAULT_TTL_SECONDS = 5 * 60L
        private const val RATE_LIMIT_KEY_PREFIX = "boj:code:rate:"
        private const val MAX_REQUESTS_PER_MINUTE = 5
        private const val RATE_LIMIT_TTL_SECONDS = 60L
    }

    data class IssuedCode(
        val sessionId: String,
        val code: String,
        val expiresInSeconds: Long
    )

    @Transactional(readOnly = true)
    fun issueVerificationCode(identifier: String?): IssuedCode {
        // Rate Limiting 체크
        val rateLimitKey = RATE_LIMIT_KEY_PREFIX + (identifier ?: "unknown")
        val currentCount = codeStore.getRateLimitCount(rateLimitKey)

        if (currentCount >= MAX_REQUESTS_PER_MINUTE) {
            throw BusinessException(
                ErrorCode.TOO_MANY_REQUESTS,
                "요청이 너무 많습니다. 1분 후 다시 시도해주세요."
            )
        }

        codeStore.incrementRateLimitCount(rateLimitKey, RATE_LIMIT_TTL_SECONDS)

        val sessionId = UUID.randomUUID().toString()
        val code = CODE_PREFIX + randomUpperAlphaNumeric(CODE_LENGTH)
        codeStore.save(sessionId, code, DEFAULT_TTL_SECONDS)
        return IssuedCode(sessionId = sessionId, code = code, expiresInSeconds = DEFAULT_TTL_SECONDS)
    }

    /**
     * BOJ 소유권 인증을 수행한다.
     * 프로필 상태 메시지에서 인증 코드를 확인하고, 성공 시 인증된 BOJ ID를 세션에 저장한다.
     * 
     * 회원가입 플로우:
     * 1. 이 API로 BOJ ID 인증 완료
     * 2. 인증된 BOJ ID를 프론트엔드에서 관리
     * 3. /api/v1/auth/signup/finalize에서 인증된 BOJ ID로 회원가입 마무리
     * 
     * @param sessionId 인증 코드 발급 시 받은 세션 ID
     * @param bojId 인증할 BOJ ID
     * @return 인증된 BOJ ID
     */
    @Transactional
    fun verifyOwnership(sessionId: String, bojId: String): String {
        val storedCode = codeStore.find(sessionId)
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "인증 코드가 만료되었거나 존재하지 않습니다.")

        val bojIdVo = BojId(bojId.trim())
        val verificationCode = BojVerificationCode(storedCode)
        val statusMessage = fetchStatusMessageOrThrow(bojIdVo)

        if (!codeMatcher.matches(statusMessage, verificationCode)) {
            throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "프로필 페이지에서 코드를 찾을 수 없습니다. 프로필 상태 메시지에 인증 코드($storedCode)를 정확히 입력하고 저장한 후, 몇 초 대기한 뒤 다시 시도해주세요."
            )
        }

        // 인증 성공: 인증된 BOJ ID를 세션에 저장 (회원가입 마무리 시 사용)
        val verifiedBojIdKey = "boj:verified:$sessionId"
        codeStore.save(verifiedBojIdKey, bojIdVo.value, DEFAULT_TTL_SECONDS)
        
        // 인증 코드는 삭제 (일회성)
        codeStore.delete(sessionId)

        log.info("BOJ 소유권 인증 성공: bojId={}, sessionId={}", bojIdVo.value, sessionId)
        return bojIdVo.value
    }

    private fun fetchStatusMessageOrThrow(bojId: BojId): BojProfileStatusMessage {
        val fetchResult = profileStatusMessageClient.fetchStatusMessage(bojId.value)
        if (fetchResult is BojProfileStatusMessageFetchResult.Found) {
            return fetchResult.statusMessage
        }
        if (fetchResult is BojProfileStatusMessageFetchResult.UserNotFound) {
            throwNotFoundException(bojId)
        }
        if (fetchResult is BojProfileStatusMessageFetchResult.AccessDenied) {
            throwAccessDeniedException(bojId)
        }
        if (fetchResult is BojProfileStatusMessageFetchResult.StatusMessageNotFound) {
            throwStatusMessageNotFoundException(bojId)
        }
        throwFetchFailedException(bojId, fetchResult)
    }

    private fun throwNotFoundException(bojId: BojId): Nothing {
        log.warn("BOJ 프로필을 찾을 수 없음: bojId={}", bojId.value)
        throw BusinessException(
            ErrorCode.COMMON_RESOURCE_NOT_FOUND,
            "백준 프로필을 찾을 수 없습니다. BOJ ID가 올바른지 확인해주세요. bojId=${bojId.value}"
        )
    }

    private fun throwAccessDeniedException(bojId: BojId): Nothing {
        log.warn("BOJ 프로필 접근 거부: bojId={}", bojId.value)
        throw BusinessException(
            ErrorCode.COMMON_INVALID_INPUT,
            "백준 프로필 페이지에 접근할 수 없습니다. 프로필이 공개되어 있는지 확인해주세요. bojId=${bojId.value}"
        )
    }

    private fun throwStatusMessageNotFoundException(bojId: BojId): Nothing {
        log.warn("BOJ 프로필 상태 메시지를 찾을 수 없음: bojId={}", bojId.value)
        throw BusinessException(
            ErrorCode.COMMON_INVALID_INPUT,
            "백준 프로필 상태 메시지를 찾을 수 없습니다. 프로필 상태 메시지에 인증 코드를 입력하고 저장한 뒤 다시 시도해주세요. bojId=${bojId.value}"
        )
    }

    private fun throwFetchFailedException(
        bojId: BojId,
        fetchResult: BojProfileStatusMessageFetchResult
    ): Nothing {
        val reason = (fetchResult as? BojProfileStatusMessageFetchResult.Failed)?.reason ?: fetchResult.toString()
        log.error("BOJ 프로필 상태 메시지 조회 실패: bojId={}, reason={}", bojId.value, reason)
        throw BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "백준 프로필 페이지를 가져오는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요. bojId=${bojId.value}"
        )
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

