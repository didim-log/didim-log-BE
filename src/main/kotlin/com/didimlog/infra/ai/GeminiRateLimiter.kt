package com.didimlog.infra.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.ratelimit.RateLimitUnavailableException
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Gemini API 호출 간격과 분·일 사용량을 Redis에서 제한한다.
 */
@Component
class GeminiRateLimiter @Autowired constructor(
    private val redisTemplate: StringRedisTemplate,
    private val properties: AiGeminiProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var clock: Clock = Clock.systemUTC()

    constructor(
        redisTemplate: StringRedisTemplate,
        properties: AiGeminiProperties,
        clock: Clock
    ) : this(redisTemplate, properties) {
        this.clock = clock
    }

    /**
     * 제한을 확인하고 허용된 호출만 사용량에 기록한다.
     */
    fun checkAndIncrement() {
        checkAndIncrement(Instant.now(clock))
    }

    private fun checkAndIncrement(now: Instant) {
        val window = createWindow(now)
        val policy = properties.rateLimit
        val result = try {
            redisTemplate.execute(
                CHECK_AND_INCREMENT_SCRIPT,
                listOf(window.rpmKey, window.rpdKey, LAST_REQUEST_KEY),
                policy.maxRpm.toString(),
                policy.maxRpd.toString(),
                window.rpmTtlMillis.toString(),
                window.rpdTtlMillis.toString(),
                (policy.minIntervalSeconds * MILLIS_PER_SECOND).toString(),
                now.toEpochMilli().toString(),
                now.epochSecond.toString()
            )
        } catch (e: DataAccessException) {
            throw RateLimitUnavailableException(e)
        }

        val decision = parseDecision(result)
        when (decision.code) {
            ALLOWED -> log.debug(
                "Gemini API Rate Limit 통과: RPM={}/{}, RPD={}/{}",
                decision.rpm,
                policy.maxRpm,
                decision.rpd,
                policy.maxRpd
            )
            MINIMUM_INTERVAL_EXCEEDED -> {
                val retrySeconds = toRetrySeconds(decision.retryAfterMillis)
                throw BusinessException(
                    ErrorCode.AI_SERVICE_BUSY,
                    "무료 사용량이 많아 잠시 대기 중입니다. " +
                        "${retrySeconds}초 후 다시 시도해주세요."
                )
            }
            RPM_EXCEEDED -> throw BusinessException(
                ErrorCode.AI_SERVICE_BUSY,
                "무료 사용량이 많아 잠시 대기 중입니다. " +
                    "${toRetrySeconds(decision.retryAfterMillis)}초 후 다시 시도해주세요."
            )
            RPD_EXCEEDED -> throw BusinessException(
                ErrorCode.AI_SERVICE_BUSY,
                "오늘의 무료 사용량을 모두 사용했습니다. 내일 다시 시도해주세요."
            )
            INVALID_STATE -> throw IllegalStateException(
                "Gemini Rate Limit Redis 상태가 올바르지 않습니다."
            )
            else -> throw IllegalStateException(
                "Gemini Rate Limit 처리 결과 코드가 올바르지 않습니다: ${decision.code}"
            )
        }
    }

    /**
     * 현재 UTC 일 버킷의 사용량을 조회한다.
     */
    fun getDailyUsage(): Long {
        return getDailyUsage(Instant.now(clock))
    }

    private fun getDailyUsage(now: Instant): Long {
        val currentDay = Math.floorDiv(now.toEpochMilli(), MILLIS_PER_DAY)
        val rpdKey = "$RPD_KEY_PREFIX$currentDay"
        val usage = try {
            redisTemplate.opsForValue().get(rpdKey)
        } catch (e: DataAccessException) {
            throw RateLimitUnavailableException(e)
        }
        if (usage == null) {
            return 0L
        }
        val parsedUsage = usage.toLongOrNull()
            ?: throw IllegalStateException("Gemini 일일 사용량이 숫자가 아닙니다.")
        check(parsedUsage >= 0L) {
            "Gemini 일일 사용량이 음수입니다."
        }
        return parsedUsage
    }

    fun isNearDailyLimit(threshold: Long): Boolean {
        return getDailyUsage() >= threshold
    }

    private fun createWindow(now: Instant): RateLimitWindow {
        val epochMillis = now.toEpochMilli()
        val currentMinute = Math.floorDiv(epochMillis, MILLIS_PER_MINUTE)
        val currentDay = Math.floorDiv(epochMillis, MILLIS_PER_DAY)
        return RateLimitWindow(
            rpmKey = "$RPM_KEY_PREFIX$currentMinute",
            rpdKey = "$RPD_KEY_PREFIX$currentDay",
            rpmTtlMillis = ((currentMinute + 1) * MILLIS_PER_MINUTE - epochMillis)
                .coerceAtLeast(1L),
            rpdTtlMillis = ((currentDay + 1) * MILLIS_PER_DAY - epochMillis)
                .coerceAtLeast(1L)
        )
    }

    private fun parseDecision(result: List<*>?): RateLimitScriptDecision {
        check(result != null && result.size == 4) {
            "Gemini Rate Limit 처리 결과 형식이 올바르지 않습니다."
        }
        val decision = RateLimitScriptDecision(
            code = result[0].asLong("code"),
            rpm = result[1].asLong("rpm"),
            rpd = result[2].asLong("rpd"),
            retryAfterMillis = result[3].asLong("retryAfterMillis")
        )
        check(decision.rpm >= 0L && decision.rpd >= 0L) {
            "Gemini Rate Limit 사용량이 올바르지 않습니다."
        }
        check(decision.retryAfterMillis >= 0L) {
            "Gemini Rate Limit 재시도 시간이 올바르지 않습니다."
        }
        return decision
    }

    private fun Any?.asLong(field: String): Long {
        return when (this) {
            is Number -> toLong()
            else -> this?.toString()?.toLongOrNull()
        } ?: throw IllegalStateException(
            "Gemini Rate Limit $field 결과가 숫자가 아닙니다."
        )
    }

    private fun toRetrySeconds(retryAfterMillis: Long): Long {
        return ((retryAfterMillis + 999L) / MILLIS_PER_SECOND).coerceAtLeast(1L)
    }

    private data class RateLimitWindow(
        val rpmKey: String,
        val rpdKey: String,
        val rpmTtlMillis: Long,
        val rpdTtlMillis: Long
    )

    private data class RateLimitScriptDecision(
        val code: Long,
        val rpm: Long,
        val rpd: Long,
        val retryAfterMillis: Long
    )

    companion object {
        private const val RPM_KEY_PREFIX = "gemini:rate:rpm:"
        private const val RPD_KEY_PREFIX = "gemini:rate:rpd:"
        private const val LAST_REQUEST_KEY = "gemini:rate:last:"

        private const val ALLOWED = 0L
        private const val MINIMUM_INTERVAL_EXCEEDED = 1L
        private const val RPM_EXCEEDED = 2L
        private const val RPD_EXCEEDED = 3L
        private const val INVALID_STATE = 4L

        private const val MILLIS_PER_SECOND = 1_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_DAY = 86_400_000L

        private val CHECK_AND_INCREMENT_SCRIPT = DefaultRedisScript(
            """
            local function parseCounter(value)
                if not value then
                    return 0
                end
                if not string.match(value, '^%d+$') then
                    return nil
                end
                return tonumber(value)
            end

            local maxRpm = tonumber(ARGV[1])
            local maxRpd = tonumber(ARGV[2])
            local rpmTtlMillis = tonumber(ARGV[3])
            local rpdTtlMillis = tonumber(ARGV[4])
            local minIntervalMillis = tonumber(ARGV[5])
            local nowEpochMillis = tonumber(ARGV[6])
            local nowEpochSeconds = ARGV[7]

            if not maxRpm or not maxRpd or not rpmTtlMillis or
                not rpdTtlMillis or not minIntervalMillis or
                not nowEpochMillis or not nowEpochSeconds or
                maxRpm <= 0 or maxRpd <= 0 or
                rpmTtlMillis <= 0 or rpdTtlMillis <= 0 or
                minIntervalMillis < 0 then
                return {4, 0, 0, 0}
            end

            local rpmValue = redis.call('GET', KEYS[1])
            local rpdValue = redis.call('GET', KEYS[2])
            local rpm = parseCounter(rpmValue)
            local rpd = parseCounter(rpdValue)
            if not rpm or not rpd then
                return {4, 0, 0, 0}
            end

            local intervalTtlMillis = redis.call('PTTL', KEYS[3])
            local legacyIntervalRemainingMillis = 0
            if intervalTtlMillis == -1 then
                local lastEpochSeconds = parseCounter(redis.call('GET', KEYS[3]))
                if not lastEpochSeconds then
                    return {4, rpm, rpd, 0}
                end
                legacyIntervalRemainingMillis =
                    minIntervalMillis -
                    (nowEpochMillis - (lastEpochSeconds * 1000 + 999))
                if legacyIntervalRemainingMillis < 0 then
                    legacyIntervalRemainingMillis = 0
                end
                if legacyIntervalRemainingMillis > minIntervalMillis then
                    legacyIntervalRemainingMillis = minIntervalMillis
                end
            end

            local rpmStoredTtlMillis = redis.call('PTTL', KEYS[1])
            local rpdStoredTtlMillis = redis.call('PTTL', KEYS[2])

            if rpmValue and
                (rpmStoredTtlMillis < 0 or rpmStoredTtlMillis > rpmTtlMillis) then
                redis.call('PEXPIRE', KEYS[1], rpmTtlMillis)
                rpmStoredTtlMillis = rpmTtlMillis
            end
            if rpdValue and
                (rpdStoredTtlMillis < 0 or rpdStoredTtlMillis > rpdTtlMillis) then
                redis.call('PEXPIRE', KEYS[2], rpdTtlMillis)
                rpdStoredTtlMillis = rpdTtlMillis
            end

            if minIntervalMillis == 0 then
                redis.call('DEL', KEYS[3])
                intervalTtlMillis = -2
            elseif intervalTtlMillis == -1 then
                if legacyIntervalRemainingMillis > 0 then
                    redis.call('PEXPIRE', KEYS[3], legacyIntervalRemainingMillis)
                    intervalTtlMillis = legacyIntervalRemainingMillis
                else
                    redis.call('DEL', KEYS[3])
                    intervalTtlMillis = -2
                end
            end

            if intervalTtlMillis > 0 then
                return {1, rpm, rpd, intervalTtlMillis}
            end
            if rpm >= maxRpm then
                local retryMillis = rpmStoredTtlMillis
                if retryMillis <= 0 then
                    retryMillis = rpmTtlMillis
                end
                return {2, rpm, rpd, retryMillis}
            end
            if rpd >= maxRpd then
                local retryMillis = rpdStoredTtlMillis
                if retryMillis <= 0 then
                    retryMillis = rpdTtlMillis
                end
                return {3, rpm, rpd, retryMillis}
            end

            local updatedRpm = rpm + 1
            local updatedRpd = rpd + 1
            redis.call('SET', KEYS[1], tostring(updatedRpm), 'PX', rpmTtlMillis)
            redis.call('SET', KEYS[2], tostring(updatedRpd), 'PX', rpdTtlMillis)
            if minIntervalMillis > 0 then
                redis.call(
                    'SET',
                    KEYS[3],
                    nowEpochSeconds,
                    'PX',
                    minIntervalMillis
                )
            end
            return {0, updatedRpm, updatedRpd, 0}
            """.trimIndent(),
            List::class.java
        )
    }
}
