package com.didimlog.global.ratelimit

import com.didimlog.global.exception.ErrorResponse
import com.didimlog.global.util.HttpRequestUtil
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Rate Limiting 인터셉터
 * 특정 경로에 대해 Rate Limiting을 적용합니다.
 */
@Component
class RateLimitInterceptor(
    private val rateLimitService: RateLimitService,
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {

    companion object {
        const val RATE_LIMIT_DECISION_ATTRIBUTE = "com.didimlog.rateLimitDecision"

        private const val RATE_LIMIT_LIMIT_HEADER = "X-Rate-Limit-Limit"
        private const val RATE_LIMIT_REMAINING_HEADER = "X-Rate-Limit-Remaining"
        private const val MAX_SIGNUP_REQUESTS = 5
        private const val MAX_LOGIN_REQUESTS = 10
        private const val MAX_PASSWORD_RESET_REQUESTS = 3
        private const val MAX_BOJ_VERIFY_REQUESTS = 10
        private const val RATE_LIMIT_WINDOW_MINUTES = 60
        private const val BOJ_VERIFY_WINDOW_MINUTES = 1
    }

    private val signupPolicy = RateLimitPolicy(
        keyPrefix = "signup",
        maxRequests = MAX_SIGNUP_REQUESTS,
        message = "회원가입 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
    )
    private val loginPolicy = RateLimitPolicy(
        keyPrefix = "login",
        maxRequests = MAX_LOGIN_REQUESTS,
        message = "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
    )
    private val passwordResetPolicy = RateLimitPolicy(
        keyPrefix = "password_reset",
        maxRequests = MAX_PASSWORD_RESET_REQUESTS,
        message = "계정 찾기 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
    )
    private val bojVerifyPolicy = RateLimitPolicy(
        keyPrefix = "boj_verify",
        maxRequests = MAX_BOJ_VERIFY_REQUESTS,
        windowMinutes = BOJ_VERIFY_WINDOW_MINUTES,
        message = "BOJ 인증 확인 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
    )
    private val policies = mapOf(
        "/api/v1/auth/signup" to signupPolicy,
        "/api/v1/auth/super-admin" to signupPolicy,
        "/api/v1/auth/login" to loginPolicy,
        "/api/v1/auth/find-account" to passwordResetPolicy,
        "/api/v1/auth/find-id" to passwordResetPolicy,
        "/api/v1/auth/find-password" to passwordResetPolicy,
        "/api/v1/auth/reset-password" to passwordResetPolicy,
        "/api/v1/auth/boj/verify" to bojVerifyPolicy
    )

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        if (!HttpMethod.POST.matches(request.method)) {
            return true
        }

        val matchedPattern = request.getAttribute(
            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
        )?.toString() ?: return true
        val policy = policies[matchedPattern] ?: return true
        val clientIp = HttpRequestUtil.getClientIpAddress(request)
        val decision = rateLimitService.checkAndRecord(
            key = "${policy.keyPrefix}:$clientIp",
            maxRequests = policy.maxRequests,
            windowMinutes = policy.windowMinutes
        )
        setRateLimitHeaders(response, decision)

        if (policy === loginPolicy) {
            request.setAttribute(RATE_LIMIT_DECISION_ATTRIBUTE, decision)
        }

        if (!decision.allowed) {
            sendRateLimitError(
                response = response,
                message = policy.message,
                retryAfterSeconds = decision.retryAfterSeconds ?: 1L
            )
        }
        return decision.allowed
    }

    private fun setRateLimitHeaders(response: HttpServletResponse, decision: RateLimitDecision) {
        response.setHeader(RATE_LIMIT_LIMIT_HEADER, decision.limit.toString())
        response.setHeader(RATE_LIMIT_REMAINING_HEADER, decision.remainingRequests.toString())
    }

    private fun sendRateLimitError(response: HttpServletResponse, message: String, retryAfterSeconds: Long) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())

        val errorResponse = ErrorResponse(
            status = HttpStatus.TOO_MANY_REQUESTS.value(),
            error = "Too Many Requests",
            code = "RATE_LIMIT_EXCEEDED",
            message = message,
            remainingAttempts = 0,
            unlockTime = calculateUnlockTime(retryAfterSeconds)
        )

        response.writer.write(objectMapper.writeValueAsString(errorResponse))
    }

    /**
     * TTL(초)을 기반으로 한국시간으로 잠금 해제 시간을 계산합니다.
     *
     * @param ttlSeconds 남은 TTL(초)
     * @return 한국시간으로 변환된 잠금 해제 시간 (ISO 8601 형식, 예: "2024-01-15T14:30:00+09:00")
     */
    private fun calculateUnlockTime(ttlSeconds: Long): String {
        val unlockInstant = Instant.now().plusSeconds(ttlSeconds)
        val koreaZone = ZoneId.of("Asia/Seoul")
        val unlockTime = ZonedDateTime.ofInstant(unlockInstant, koreaZone)
        return unlockTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private data class RateLimitPolicy(
        val keyPrefix: String,
        val maxRequests: Int,
        val windowMinutes: Int = RATE_LIMIT_WINDOW_MINUTES,
        val message: String
    )
}
