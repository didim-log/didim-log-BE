package com.didimlog.application.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * AI 사용량 추적 및 제한 서비스
 * Redis를 사용하여 일일 사용량을 추적하고 제한을 관리합니다.
 */
@Service
class AiUsageService(
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Redis 키 패턴
        private const val CONFIG_GLOBAL_LIMIT = "AI_CONFIG:LIMIT:GLOBAL"
        private const val CONFIG_USER_LIMIT = "AI_CONFIG:LIMIT:USER"
        private const val CONFIG_ENABLED = "AI_SERVICE:ENABLED"
        private const val USAGE_GLOBAL_PREFIX = "AI_USAGE:GLOBAL:"
        private const val USAGE_USER_PREFIX = "AI_USAGE:USER:"

        // 기본값
        private const val DEFAULT_GLOBAL_LIMIT = 1000
        private const val DEFAULT_USER_LIMIT = 5
        private const val DEFAULT_ENABLED = true

        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    /**
     * AI 서비스 사용 가능 여부를 확인합니다.
     *
     * @param userId 사용자 ID (bojId)
     * @return AiStatus (사용 가능 여부 및 현재 상태)
     * @throws BusinessException 사용 불가능한 경우
     */
    fun checkAvailability(userId: String): AiStatus {
        // 1. 서비스 활성화 여부 확인
        val isEnabled = isServiceEnabled()
        if (!isEnabled) {
            throw BusinessException(ErrorCode.AI_SERVICE_DISABLED, "AI 서비스가 일시 중지되었습니다.")
        }

        // 2. 제한값 조회
        val globalLimit = getGlobalLimit()
        val userLimit = getUserLimit()

        // 3. 전역 사용량 확인
        val todayGlobalUsage = getTodayGlobalUsage()
        if (todayGlobalUsage >= globalLimit) {
            throw BusinessException(
                ErrorCode.AI_GLOBAL_LIMIT_EXCEEDED,
                "현재 서비스 이용량이 많아 AI 기능이 일시 중지되었습니다."
            )
        }

        // 4. 사용자 사용량 확인
        val todayUserUsage = getTodayUserUsage(userId)
        if (todayUserUsage >= userLimit) {
            throw BusinessException(
                ErrorCode.AI_USER_LIMIT_EXCEEDED,
                "일일 AI 사용 횟수(${userLimit}회)를 초과했습니다. 내일 다시 이용해주세요."
            )
        }

        return AiStatus(
            isEnabled = true,
            todayGlobalUsage = todayGlobalUsage,
            globalLimit = globalLimit,
            userLimit = userLimit,
            todayUserUsage = todayUserUsage
        )
    }

    /**
     * AI 사용량을 증가시킵니다 (원자적 연산).
     *
     * @param userId 사용자 ID
     */
    fun incrementUsage(userId: String) {
        val today = LocalDate.now().format(DATE_FORMATTER)
        val globalKey = "$USAGE_GLOBAL_PREFIX$today"
        val userKey = "$USAGE_USER_PREFIX$userId:$today"

        // 원자적 증가 (INCR)
        redisTemplate.opsForValue().increment(globalKey)
        redisTemplate.opsForValue().increment(userKey)

        // TTL 설정 (다음 날 자정까지 유지)
        val ttlSeconds = getSecondsUntilMidnight()
        redisTemplate.expire(globalKey, java.time.Duration.ofSeconds(ttlSeconds))
        redisTemplate.expire(userKey, java.time.Duration.ofSeconds(ttlSeconds))

        log.debug("AI 사용량 증가: userId=$userId, globalKey=$globalKey, userKey=$userKey")
    }

    /**
     * AI 서비스를 긴급 중지합니다.
     */
    fun emergencyStop() {
        redisTemplate.opsForValue().set(CONFIG_ENABLED, "false")
        log.warn("AI 서비스가 긴급 중지되었습니다.")
    }

    /**
     * AI 서비스 활성화/비활성화를 설정합니다.
     *
     * @param enabled 활성화 여부
     */
    fun setServiceEnabled(enabled: Boolean) {
        redisTemplate.opsForValue().set(CONFIG_ENABLED, enabled.toString())
        log.info("AI 서비스 상태 변경: enabled=$enabled")
    }

    /**
     * AI 사용량 제한을 업데이트합니다.
     *
     * @param globalLimit 전역 일일 제한
     * @param userLimit 사용자 일일 제한
     */
    fun updateLimits(globalLimit: Int, userLimit: Int) {
        redisTemplate.opsForValue().set(CONFIG_GLOBAL_LIMIT, globalLimit.toString())
        redisTemplate.opsForValue().set(CONFIG_USER_LIMIT, userLimit.toString())
        log.info("AI 사용량 제한 업데이트: globalLimit=$globalLimit, userLimit=$userLimit")
    }

    /**
     * 현재 AI 서비스 상태를 조회합니다.
     *
     * @return AiStatus
     */
    fun getStatus(): AiStatus {
        val isEnabled = isServiceEnabled()
        val globalLimit = getGlobalLimit()
        val userLimit = getUserLimit()
        val todayGlobalUsage = getTodayGlobalUsage()

        return AiStatus(
            isEnabled = isEnabled,
            todayGlobalUsage = todayGlobalUsage,
            globalLimit = globalLimit,
            userLimit = userLimit,
            todayUserUsage = null // 사용자별 사용량은 사용자 ID가 필요하므로 null
        )
    }

    /**
     * 서비스 활성화 여부를 확인합니다.
     */
    private fun isServiceEnabled(): Boolean {
        val value = redisTemplate.opsForValue().get(CONFIG_ENABLED)
        val isEnabled = value?.toBoolean() ?: DEFAULT_ENABLED
        log.debug("AI service enabled check: key=$CONFIG_ENABLED, value=$value, result=$isEnabled")
        return isEnabled
    }

    /**
     * 전역 일일 제한을 조회합니다.
     */
    private fun getGlobalLimit(): Int {
        val value = redisTemplate.opsForValue().get(CONFIG_GLOBAL_LIMIT)
        return value?.toInt() ?: DEFAULT_GLOBAL_LIMIT
    }

    /**
     * 사용자 일일 제한을 조회합니다.
     */
    private fun getUserLimit(): Int {
        val value = redisTemplate.opsForValue().get(CONFIG_USER_LIMIT)
        return value?.toInt() ?: DEFAULT_USER_LIMIT
    }

    /**
     * 오늘의 전역 사용량을 조회합니다.
     */
    private fun getTodayGlobalUsage(): Int {
        val today = LocalDate.now().format(DATE_FORMATTER)
        val key = "$USAGE_GLOBAL_PREFIX$today"
        val value = redisTemplate.opsForValue().get(key)
        return value?.toInt() ?: 0
    }

    /**
     * 오늘의 사용자 사용량을 조회합니다.
     */
    private fun getTodayUserUsage(userId: String): Int {
        val today = LocalDate.now().format(DATE_FORMATTER)
        val key = "$USAGE_USER_PREFIX$userId:$today"
        val value = redisTemplate.opsForValue().get(key)
        val usage = value?.toInt() ?: 0
        log.debug("User usage check: userId=$userId, key=$key, value=$value, usage=$usage")
        return usage
    }
    
    /**
     * 사용자의 AI 사용량 정보를 조회합니다.
     * 
     * @param userId 사용자 ID (bojId)
     * @return 사용자 AI 사용량 정보
     */
    fun getUserUsage(userId: String): UserUsageInfo {
        val isEnabled = isServiceEnabled()
        val userLimit = getUserLimit()
        val todayUserUsage = getTodayUserUsage(userId)
        val remaining = (userLimit - todayUserUsage).coerceAtLeast(0)
        
        return UserUsageInfo(
            limit = userLimit,
            usage = todayUserUsage,
            remaining = remaining,
            isServiceEnabled = isEnabled
        )
    }
    
    /**
     * 사용자 AI 사용량 정보
     */
    data class UserUsageInfo(
        val limit: Int,
        val usage: Int,
        val remaining: Int,
        val isServiceEnabled: Boolean
    )

    /**
     * 자정까지 남은 초를 계산합니다.
     */
    private fun getSecondsUntilMidnight(): Long {
        val now = java.time.LocalDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        return java.time.Duration.between(now, midnight).seconds
    }

    /**
     * AI 서비스 상태 정보
     */
    data class AiStatus(
        val isEnabled: Boolean,
        val todayGlobalUsage: Int,
        val globalLimit: Int,
        val userLimit: Int,
        val todayUserUsage: Int? = null
    )
}


