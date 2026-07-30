package com.didimlog.global.ratelimit

import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.dao.QueryTimeoutException
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * Rate Limiting 서비스
 * Redis를 사용하여 IP 또는 사용자별 요청 제한을 구현합니다.
 */
@Service
class RateLimitService(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        private const val RATE_LIMIT_PREFIX = "rate_limit:"

        private val CHECK_AND_RECORD_SCRIPT = DefaultRedisScript(
            """
            local maxRequests = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local storedCount = redis.call('GET', KEYS[1])
            local isNewKey = not storedCount
            local currentCount = tonumber(storedCount) or 0
            local ttlMillis = redis.call('PTTL', KEYS[1])

            if storedCount and ttlMillis < 0 then
                redis.call('PEXPIRE', KEYS[1], windowMillis)
                ttlMillis = windowMillis
            end

            if currentCount >= maxRequests then
                return {0, currentCount, ttlMillis}
            end

            local updatedCount = redis.call('INCR', KEYS[1])
            if isNewKey then
                redis.call('PEXPIRE', KEYS[1], windowMillis)
                ttlMillis = windowMillis
            end

            return {1, updatedCount, ttlMillis}
            """.trimIndent(),
            List::class.java
        )
    }

    /**
     * 고정 시간 구간의 Rate Limit을 원자적으로 확인하고 기록합니다.
     *
     * @param key Rate Limit 키 (IP 주소 또는 사용자 ID)
     * @param maxRequests 최대 요청 수
     * @param windowMinutes 시간 윈도우 (분)
     * @return 허용 여부와 남은 요청 수, 재시도 가능 시간
     */
    fun checkAndRecord(
        key: String,
        maxRequests: Int,
        windowMinutes: Int
    ): RateLimitDecision {
        require(key.isNotBlank()) { "key must not be blank" }
        require(maxRequests > 0) { "maxRequests must be greater than zero" }
        require(windowMinutes > 0) { "windowMinutes must be greater than zero" }

        val redisKey = "$RATE_LIMIT_PREFIX$key"
        val result = try {
            redisTemplate.execute(
                CHECK_AND_RECORD_SCRIPT,
                listOf(redisKey),
                maxRequests.toString(),
                TimeUnit.MINUTES.toMillis(windowMinutes.toLong()).toString()
            )
        } catch (e: RedisConnectionFailureException) {
            throw RateLimitUnavailableException(e)
        } catch (e: QueryTimeoutException) {
            throw RateLimitUnavailableException(e)
        }
        check(result.size == 3) { "Rate Limit 처리 결과 형식이 올바르지 않습니다." }

        val allowedFlag = result[0].asLong("allowed")
        check(allowedFlag == 0L || allowedFlag == 1L) {
            "Rate Limit 허용 결과가 올바르지 않습니다."
        }
        val allowed = allowedFlag == 1L
        val currentCount = result[1].asLong("count")
        check(currentCount >= 0L) { "Rate Limit 요청 수가 올바르지 않습니다." }
        val ttlMillis = result[2].asLong("ttl")
        check(ttlMillis >= 0L) { "Rate Limit 만료 시간이 올바르지 않습니다." }

        return RateLimitDecision(
            allowed = allowed,
            limit = maxRequests,
            remainingRequests = (maxRequests.toLong() - currentCount)
                .coerceAtLeast(0L)
                .toInt(),
            retryAfterSeconds = if (allowed) {
                null
            } else {
                ((ttlMillis + 999L) / 1_000L).coerceAtLeast(1L)
            }
        )
    }

    private fun Any?.asLong(field: String): Long {
        return when (this) {
            is Number -> toLong()
            else -> this?.toString()?.toLongOrNull()
        } ?: throw IllegalStateException("Rate Limit $field 결과가 숫자가 아닙니다.")
    }
}
