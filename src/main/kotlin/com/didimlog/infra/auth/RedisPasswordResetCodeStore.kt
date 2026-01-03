package com.didimlog.infra.auth

import com.didimlog.application.auth.PasswordResetCodeStore
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisPasswordResetCodeStore(
    private val redisTemplate: StringRedisTemplate
) : PasswordResetCodeStore {

    companion object {
        private const val KEY_PREFIX = "password:reset:"
    }

    override fun save(resetCode: String, studentId: String, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(key(resetCode), studentId, Duration.ofSeconds(ttlSeconds))
    }

    override fun find(resetCode: String): String? {
        return redisTemplate.opsForValue().get(key(resetCode))
    }

    override fun delete(resetCode: String) {
        redisTemplate.delete(key(resetCode))
    }

    private fun key(resetCode: String): String {
        return KEY_PREFIX + resetCode
    }
}





