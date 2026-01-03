package com.didimlog.global.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
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
    }

    /**
     * Rate Limit을 확인하고 적용합니다.
     *
     * @param key Rate Limit 키 (IP 주소 또는 사용자 ID)
     * @param maxRequests 최대 요청 수
     * @param windowMinutes 시간 윈도우 (분)
     * @return Rate Limit을 초과하지 않았으면 true, 초과했으면 false
     */
    fun isAllowed(key: String, maxRequests: Int, windowMinutes: Int): Boolean {
        val redisKey = "$RATE_LIMIT_PREFIX$key"
        val currentCount = redisTemplate.opsForValue().get(redisKey)?.toIntOrNull() ?: 0

        if (currentCount >= maxRequests) {
            return false
        }

        redisTemplate.opsForValue().increment(redisKey)
        redisTemplate.expire(redisKey, windowMinutes.toLong(), TimeUnit.MINUTES)
        return true
    }

    /**
     * 남은 요청 수를 조회합니다.
     *
     * @param key Rate Limit 키
     * @param maxRequests 최대 요청 수
     * @return 남은 요청 수
     */
    fun getRemainingRequests(key: String, maxRequests: Int): Int {
        val redisKey = "$RATE_LIMIT_PREFIX$key"
        val currentCount = redisTemplate.opsForValue().get(redisKey)?.toIntOrNull() ?: 0
        return (maxRequests - currentCount).coerceAtLeast(0)
    }

    /**
     * Rate Limit 키를 삭제합니다. (테스트용)
     */
    fun reset(key: String) {
        val redisKey = "$RATE_LIMIT_PREFIX$key"
        redisTemplate.delete(redisKey)
    }
}


