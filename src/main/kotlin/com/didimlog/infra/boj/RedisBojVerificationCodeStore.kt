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

    private fun key(sessionId: String): String {
        return KEY_PREFIX + sessionId
    }
}

