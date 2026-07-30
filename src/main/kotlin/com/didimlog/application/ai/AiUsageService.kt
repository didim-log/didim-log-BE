package com.didimlog.application.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
        private const val USAGE_RESERVATION_PREFIX = "AI_USAGE:RESERVATION:"

        // 기본값
        private const val DEFAULT_GLOBAL_LIMIT = 1000
        private const val DEFAULT_USER_LIMIT = 5
        private const val DEFAULT_ENABLED = true
        private const val DEFAULT_REQUIRE_BOJ_FOR_AI_REVIEW = true
        private const val MAX_RESERVATION_KEY_ATTEMPTS = 3

        private const val RESERVATION_SUCCEEDED = 0L
        private const val SERVICE_DISABLED = 1L
        private const val GLOBAL_LIMIT_EXCEEDED = 2L
        private const val USER_LIMIT_EXCEEDED = 3L
        private const val RESERVATION_KEY_COLLISION = 4L
        private const val INVALID_USAGE_COUNTER = -1L

        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        private val RESERVE_USAGE_SCRIPT = DefaultRedisScript(
            """
            local function parseInteger(value, fallback)
                if not value or not string.match(value, '^%-?%d+$') then
                    return fallback
                end
                return tonumber(value) or fallback
            end

            local enabled = redis.call('GET', KEYS[1])
            local globalLimit = parseInteger(redis.call('GET', KEYS[2]), tonumber(ARGV[1]))
            local userLimit = parseInteger(redis.call('GET', KEYS[3]), tonumber(ARGV[2]))
            local globalValue = redis.call('GET', KEYS[4])
            local userValue = redis.call('GET', KEYS[5])
            local globalUsage = parseInteger(globalValue, 0)
            local userUsage = parseInteger(userValue, 0)
            local ttlMillis = tonumber(ARGV[3])

            if enabled and string.lower(enabled) ~= 'true' then
                return 1
            end
            if globalUsage >= globalLimit then
                return 2
            end
            if userUsage >= userLimit then
                return 3
            end
            if not redis.call('SET', KEYS[6], '1', 'NX', 'PX', ttlMillis) then
                return 4
            end

            redis.call('SET', KEYS[4], tostring(globalUsage + 1), 'PX', ttlMillis)
            redis.call('SET', KEYS[5], tostring(userUsage + 1), 'PX', ttlMillis)
            return 0
            """.trimIndent(),
            Long::class.java
        )

        private val RELEASE_USAGE_SCRIPT = DefaultRedisScript(
            """
            local function parseCounter(value)
                if not value then
                    return 0
                end
                if not string.match(value, '^%-?%d+$') then
                    return nil
                end
                return tonumber(value)
            end

            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end

            local globalUsage = parseCounter(redis.call('GET', KEYS[2]))
            local userUsage = parseCounter(redis.call('GET', KEYS[3]))
            if not globalUsage or not userUsage then
                return -1
            end

            redis.call('DEL', KEYS[1])

            if globalUsage > 0 then
                local remainingGlobalUsage = globalUsage - 1
                if remainingGlobalUsage == 0 then
                    redis.call('DEL', KEYS[2])
                else
                    redis.call('SET', KEYS[2], tostring(remainingGlobalUsage), 'KEEPTTL')
                end
            end

            if userUsage > 0 then
                local remainingUserUsage = userUsage - 1
                if remainingUserUsage == 0 then
                    redis.call('DEL', KEYS[3])
                else
                    redis.call('SET', KEYS[3], tostring(remainingUserUsage), 'KEEPTTL')
                end
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )
    }

    /**
     * AI 서비스 사용 가능 여부를 확인합니다.
     *
     * @param userId 변경되지 않는 학생 ID
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
     * AI 호출 전에 일일 사용량을 원자적으로 예약합니다.
     */
    fun reserveUsage(userId: String): UsageReservation {
        val dailyKeys = createDailyUsageKeys(userId)

        repeat(MAX_RESERVATION_KEY_ATTEMPTS) {
            val reservationKey =
                "$USAGE_RESERVATION_PREFIX$userId:${dailyKeys.date}:${UUID.randomUUID()}"
            val result = redisTemplate.execute(
                RESERVE_USAGE_SCRIPT,
                listOf(
                    CONFIG_ENABLED,
                    CONFIG_GLOBAL_LIMIT,
                    CONFIG_USER_LIMIT,
                    dailyKeys.globalUsageKey,
                    dailyKeys.userUsageKey,
                    reservationKey
                ),
                DEFAULT_GLOBAL_LIMIT.toString(),
                DEFAULT_USER_LIMIT.toString(),
                dailyKeys.ttlMillis.toString()
            )

            when (result) {
                RESERVATION_SUCCEEDED -> {
                    log.debug(
                        "AI 사용량 예약: userId={}, globalKey={}, userKey={}",
                        userId,
                        dailyKeys.globalUsageKey,
                        dailyKeys.userUsageKey
                    )
                    return UsageReservation(
                        reservationKey = reservationKey,
                        globalUsageKey = dailyKeys.globalUsageKey,
                        userUsageKey = dailyKeys.userUsageKey
                    )
                }
                SERVICE_DISABLED -> throw BusinessException(
                    ErrorCode.AI_SERVICE_DISABLED,
                    "AI 서비스가 일시 중지되었습니다."
                )
                GLOBAL_LIMIT_EXCEEDED -> throw BusinessException(
                    ErrorCode.AI_GLOBAL_LIMIT_EXCEEDED,
                    "현재 서비스 이용량이 많아 AI 기능이 일시 중지되었습니다."
                )
                USER_LIMIT_EXCEEDED -> {
                    val userLimit = getAvailabilitySnapshot(userId).userLimit
                    throw BusinessException(
                        ErrorCode.AI_USER_LIMIT_EXCEEDED,
                        "일일 AI 사용 횟수(${userLimit}회)를 초과했습니다. 내일 다시 이용해주세요."
                    )
                }
                RESERVATION_KEY_COLLISION -> Unit
                else -> throw IllegalStateException("알 수 없는 AI 사용량 예약 결과입니다. result=$result")
            }
        }

        throw IllegalStateException("AI 사용량 예약 키를 생성할 수 없습니다.")
    }

    /**
     * 실패한 AI 호출의 사용량 예약을 한 번만 반환합니다.
     */
    fun releaseUsage(reservation: UsageReservation): Boolean {
        val result = redisTemplate.execute(
            RELEASE_USAGE_SCRIPT,
            listOf(
                reservation.reservationKey,
                reservation.globalUsageKey,
                reservation.userUsageKey
            )
        )
        if (result == INVALID_USAGE_COUNTER) {
            throw IllegalStateException("AI 사용량 카운터가 올바른 정수가 아닙니다.")
        }
        val released = result == 1L

        log.debug("AI 사용량 예약 해제: released={}", released)
        return released
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
     * @param userId 변경되지 않는 학생 ID
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

    private fun createDailyUsageKeys(userId: String): DailyUsageKeys {
        val now = LocalDateTime.now()
        val date = now.toLocalDate().format(DATE_FORMATTER)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val ttlMillis = Duration.between(now, midnight).toMillis().coerceAtLeast(1L)
        return DailyUsageKeys(
            date = date,
            globalUsageKey = "$USAGE_GLOBAL_PREFIX$date",
            userUsageKey = "$USAGE_USER_PREFIX$userId:$date",
            ttlMillis = ttlMillis
        )
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

    data class UsageReservation internal constructor(
        internal val reservationKey: String,
        internal val globalUsageKey: String,
        internal val userUsageKey: String
    )

    private data class DailyUsageKeys(
        val date: String,
        val globalUsageKey: String,
        val userUsageKey: String,
        val ttlMillis: Long
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
