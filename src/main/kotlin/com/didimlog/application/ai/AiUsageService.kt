package com.didimlog.application.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.SessionCallback
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
        private const val CONFIG_REQUIRE_BOJ_FOR_AI_REVIEW = "AI_CONFIG:REQUIRE_BOJ_FOR_AI_REVIEW"
        private const val USAGE_GLOBAL_PREFIX = "AI_USAGE:GLOBAL:"
        private const val USAGE_USER_PREFIX = "AI_USAGE:USER:"

        // 기본값
        private const val DEFAULT_GLOBAL_LIMIT = 1000
        private const val DEFAULT_USER_LIMIT = 5
        private const val DEFAULT_ENABLED = true
        private const val DEFAULT_REQUIRE_BOJ_FOR_AI_REVIEW = true

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
        val snapshot = getAvailabilitySnapshot(userId)

        if (!snapshot.isEnabled) {
            throw BusinessException(ErrorCode.AI_SERVICE_DISABLED, "AI 서비스가 일시 중지되었습니다.")
        }
        if (snapshot.todayGlobalUsage >= snapshot.globalLimit) {
            throw BusinessException(
                ErrorCode.AI_GLOBAL_LIMIT_EXCEEDED,
                "현재 서비스 이용량이 많아 AI 기능이 일시 중지되었습니다."
            )
        }
        if (snapshot.todayUserUsage >= snapshot.userLimit) {
            throw BusinessException(
                ErrorCode.AI_USER_LIMIT_EXCEEDED,
                "일일 AI 사용 횟수(${snapshot.userLimit}회)를 초과했습니다. 내일 다시 이용해주세요."
            )
        }

        return AiStatus(
            isEnabled = true,
            todayGlobalUsage = snapshot.todayGlobalUsage,
            globalLimit = snapshot.globalLimit,
            userLimit = snapshot.userLimit,
            requireBojForAiReview = snapshot.requireBojForAiReview,
            todayUserUsage = snapshot.todayUserUsage
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
        val ttlSeconds = getSecondsUntilMidnight()

        redisTemplate.executePipelined(object : SessionCallback<Unit> {
            override fun <K : Any?, V : Any?> execute(operations: RedisOperations<K, V>): Unit? {
                @Suppress("UNCHECKED_CAST")
                val stringOps = operations as RedisOperations<String, String>
                stringOps.opsForValue().increment(globalKey)
                stringOps.opsForValue().increment(userKey)
                stringOps.expire(globalKey, java.time.Duration.ofSeconds(ttlSeconds))
                stringOps.expire(userKey, java.time.Duration.ofSeconds(ttlSeconds))
                return null
            }
        })

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
     * AI 리뷰 요청 시 BOJ 연동 사용자를 필수로 요구할지 설정합니다.
     *
     * @param required true면 BOJ 연동 사용자만 AI 리뷰 요청 가능
     */
    fun setRequireBojForAiReview(required: Boolean) {
        redisTemplate.opsForValue().set(CONFIG_REQUIRE_BOJ_FOR_AI_REVIEW, required.toString())
        log.info("AI 리뷰 BOJ 연동 필수 정책 변경: required=$required")
    }

    /**
     * AI 사용량 제한을 업데이트합니다.
     *
     * @param globalLimit 전역 일일 제한
     * @param userLimit 사용자 일일 제한
     */
    fun updateLimits(globalLimit: Int, userLimit: Int) {
        if (userLimit > globalLimit) {
            throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "userLimit은 globalLimit을 초과할 수 없습니다. userLimit=$userLimit, globalLimit=$globalLimit"
            )
        }
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
        val snapshot = getAvailabilitySnapshot(userId = null)

        return AiStatus(
            isEnabled = snapshot.isEnabled,
            todayGlobalUsage = snapshot.todayGlobalUsage,
            globalLimit = snapshot.globalLimit,
            userLimit = snapshot.userLimit,
            requireBojForAiReview = snapshot.requireBojForAiReview,
            todayUserUsage = null // 사용자별 사용량은 사용자 ID가 필요하므로 null
        )
    }

    /**
     * AI 리뷰 요청 시 BOJ 연동 사용자를 필수로 요구하는지 조회합니다.
     */
    fun isRequireBojForAiReview(): Boolean {
        val values = multiGet(listOf(CONFIG_REQUIRE_BOJ_FOR_AI_REVIEW))
        return values[CONFIG_REQUIRE_BOJ_FOR_AI_REVIEW]?.toBoolean() ?: DEFAULT_REQUIRE_BOJ_FOR_AI_REVIEW
    }

    /**
     * 서비스 활성화 여부를 확인합니다.
     */
    private fun isServiceEnabled(): Boolean {
        val value = multiGet(listOf(CONFIG_ENABLED))[CONFIG_ENABLED]
        val isEnabled = value?.toBoolean() ?: DEFAULT_ENABLED
        log.debug("AI service enabled check: key=$CONFIG_ENABLED, value=$value, result=$isEnabled")
        return isEnabled
    }

    /**
     * 전역 일일 제한을 조회합니다.
     */
    private fun getGlobalLimit(): Int {
        val value = multiGet(listOf(CONFIG_GLOBAL_LIMIT))[CONFIG_GLOBAL_LIMIT]
        return value?.toInt() ?: DEFAULT_GLOBAL_LIMIT
    }

    /**
     * 사용자 일일 제한을 조회합니다.
     */
    private fun getUserLimit(): Int {
        val value = multiGet(listOf(CONFIG_USER_LIMIT))[CONFIG_USER_LIMIT]
        return value?.toInt() ?: DEFAULT_USER_LIMIT
    }

    /**
     * 오늘의 전역 사용량을 조회합니다.
     */
    private fun getTodayGlobalUsage(): Int {
        val today = LocalDate.now().format(DATE_FORMATTER)
        val key = "$USAGE_GLOBAL_PREFIX$today"
        val value = multiGet(listOf(key))[key]
        return value?.toInt() ?: 0
    }

    /**
     * 오늘의 사용자 사용량을 조회합니다.
     */
    private fun getTodayUserUsage(userId: String): Int {
        val today = LocalDate.now().format(DATE_FORMATTER)
        val key = "$USAGE_USER_PREFIX$userId:$today"
        val value = multiGet(listOf(key))[key]
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
        val snapshot = getAvailabilitySnapshot(userId)
        val remaining = (snapshot.userLimit - snapshot.todayUserUsage).coerceAtLeast(0)
        
        return UserUsageInfo(
            limit = snapshot.userLimit,
            usage = snapshot.todayUserUsage,
            remaining = remaining,
            isServiceEnabled = snapshot.isEnabled
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

    private fun getAvailabilitySnapshot(userId: String?): AvailabilitySnapshot {
        val today = LocalDate.now().format(DATE_FORMATTER)
        val globalUsageKey = "$USAGE_GLOBAL_PREFIX$today"
        val keys = mutableListOf(
            CONFIG_ENABLED,
            CONFIG_GLOBAL_LIMIT,
            CONFIG_USER_LIMIT,
            CONFIG_REQUIRE_BOJ_FOR_AI_REVIEW,
            globalUsageKey
        )
        val userUsageKey = if (userId == null) {
            null
        } else {
            "$USAGE_USER_PREFIX$userId:$today".also { keys.add(it) }
        }
        val values = multiGet(keys)
        val isEnabled = values[CONFIG_ENABLED]?.toBoolean() ?: DEFAULT_ENABLED
        val globalLimit = values[CONFIG_GLOBAL_LIMIT]?.toIntOrNull() ?: DEFAULT_GLOBAL_LIMIT
        val userLimit = values[CONFIG_USER_LIMIT]?.toIntOrNull() ?: DEFAULT_USER_LIMIT
        val requireBojForAiReview = values[CONFIG_REQUIRE_BOJ_FOR_AI_REVIEW]?.toBoolean() ?: DEFAULT_REQUIRE_BOJ_FOR_AI_REVIEW
        val todayGlobalUsage = values[globalUsageKey]?.toIntOrNull() ?: 0
        val todayUserUsage = if (userUsageKey == null) {
            0
        } else {
            values[userUsageKey]?.toIntOrNull() ?: 0
        }
        return AvailabilitySnapshot(
            isEnabled = isEnabled,
            globalLimit = globalLimit,
            userLimit = userLimit,
            requireBojForAiReview = requireBojForAiReview,
            todayGlobalUsage = todayGlobalUsage,
            todayUserUsage = todayUserUsage
        )
    }

    private fun multiGet(keys: List<String>): Map<String, String?> {
        if (keys.isEmpty()) {
            return emptyMap()
        }
        val values = redisTemplate.opsForValue().multiGet(keys) ?: emptyList()
        val result = LinkedHashMap<String, String?>(keys.size)
        for (index in keys.indices) {
            result[keys[index]] = values.getOrNull(index)
        }
        return result
    }

    /**
     * AI 서비스 상태 정보
     */
    data class AiStatus(
        val isEnabled: Boolean,
        val todayGlobalUsage: Int,
        val globalLimit: Int,
        val userLimit: Int,
        val requireBojForAiReview: Boolean = DEFAULT_REQUIRE_BOJ_FOR_AI_REVIEW,
        val todayUserUsage: Int? = null
    )

    private data class AvailabilitySnapshot(
        val isEnabled: Boolean,
        val globalLimit: Int,
        val userLimit: Int,
        val requireBojForAiReview: Boolean,
        val todayGlobalUsage: Int,
        val todayUserUsage: Int
    )
}
