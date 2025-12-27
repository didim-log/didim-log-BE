package com.didimlog.infra.boj

import com.didimlog.application.auth.boj.BojVerificationCodeStore
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisBojVerificationCodeStore(
    private val redisTemplate: StringRedisTemplate
) : BojVerificationCodeStore {

    companion object {
        private const val KEY_PREFIX = "boj:verify:"
        private const val RATE_LIMIT_KEY_PREFIX = "boj:rate:"
    }

    override fun save(sessionId: String, code: String, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(key(sessionId), code, Duration.ofSeconds(ttlSeconds))
    }

    override fun find(sessionId: String): String? {
        return redisTemplate.opsForValue().get(key(sessionId))
    }

    override fun delete(sessionId: String) {
        redisTemplate.delete(key(sessionId))
    }

    override fun getRateLimitCount(key: String): Long {
        val count = redisTemplate.opsForValue().get(rateLimitKey(key))
        return count?.toLongOrNull() ?: 0L
    }

    override fun incrementRateLimitCount(key: String, ttlSeconds: Long) {
        val rateLimitKey = rateLimitKey(key)
        val currentCount = redisTemplate.opsForValue().increment(rateLimitKey) ?: 1L
        if (currentCount == 1L) {
            // 첫 요청인 경우 TTL 설정
            redisTemplate.expire(rateLimitKey, Duration.ofSeconds(ttlSeconds))
        }
    }

    private fun key(sessionId: String): String {
        return KEY_PREFIX + sessionId
    }

    private fun rateLimitKey(key: String): String {
        return RATE_LIMIT_KEY_PREFIX + key
    }
}

