package com.didimlog.global.ratelimit

import com.didimlog.global.exception.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
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
        private const val MAX_SIGNUP_REQUESTS = 5
        private const val MAX_LOGIN_REQUESTS = 10
        private const val MAX_PASSWORD_RESET_REQUESTS = 3
        private const val RATE_LIMIT_WINDOW_MINUTES = 60
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // 관리자 계정은 Rate Limit 적용 안 함
        if (isAdmin()) {
            return true
        }

        val path = request.requestURI
        val clientIp = getClientIp(request)

        when {
            path == "/api/v1/auth/signup" -> {
                if (!rateLimitService.isAllowed("signup:$clientIp", MAX_SIGNUP_REQUESTS, RATE_LIMIT_WINDOW_MINUTES)) {
                    val ttlSeconds = rateLimitService.getTtlSeconds("signup:$clientIp")
                    sendRateLimitError(response, "회원가입 요청이 너무 많습니다. 1시간 후 다시 시도해주세요.", ttlSeconds)
                    return false
                }
            }
            path == "/api/v1/auth/login" -> {
                if (!rateLimitService.isAllowed("login:$clientIp", MAX_LOGIN_REQUESTS, RATE_LIMIT_WINDOW_MINUTES)) {
                    val ttlSeconds = rateLimitService.getTtlSeconds("login:$clientIp")
                    sendRateLimitError(response, "로그인 요청이 너무 많습니다. 1시간 후 다시 시도해주세요.", ttlSeconds)
                    return false
                }
            }
            path == "/api/v1/auth/find-account" || path == "/api/v1/auth/reset-password" -> {
                // IP 기반 Rate Limiting (Controller에서 email 기반 추가 처리)
                if (!rateLimitService.isAllowed("password_reset:$clientIp", MAX_PASSWORD_RESET_REQUESTS, RATE_LIMIT_WINDOW_MINUTES)) {
                    val ttlSeconds = rateLimitService.getTtlSeconds("password_reset:$clientIp")
                    sendRateLimitError(response, "비밀번호 찾기 요청이 너무 많습니다. 1시간 후 다시 시도해주세요.", ttlSeconds)
                    return false
                }
            }
        }

        return true
    }

    /**
     * 현재 사용자가 관리자인지 확인합니다.
     */
    private fun isAdmin(): Boolean {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        return authentication?.authorities?.any { it == SimpleGrantedAuthority("ROLE_ADMIN") } == true
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (xForwardedFor != null && xForwardedFor.isNotEmpty()) {
            return xForwardedFor.split(",")[0].trim()
        }
        val xRealIp = request.getHeader("X-Real-IP")
        if (xRealIp != null && xRealIp.isNotEmpty()) {
            return xRealIp
        }
        return request.remoteAddr ?: "unknown"
    }

    private fun sendRateLimitError(response: HttpServletResponse, message: String, ttlSeconds: Long?) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"

        val unlockTime = ttlSeconds?.let { calculateUnlockTime(it) }

        val errorResponse = ErrorResponse(
            status = HttpStatus.TOO_MANY_REQUESTS.value(),
            error = "Too Many Requests",
            code = "RATE_LIMIT_EXCEEDED",
            message = message,
            unlockTime = unlockTime
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
}

