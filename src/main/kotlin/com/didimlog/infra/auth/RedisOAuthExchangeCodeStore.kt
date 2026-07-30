package com.didimlog.infra.auth

import com.didimlog.application.auth.oauth.OAuthExchangeCodeStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisOAuthExchangeCodeStore(
    private val redisTemplate: StringRedisTemplate
) : OAuthExchangeCodeStore {

    override fun save(code: String, studentId: String, ttlSeconds: Long): Boolean {
        return redisTemplate.opsForValue().setIfAbsent(
            key(code),
            studentId,
            Duration.ofSeconds(ttlSeconds)
        ) == true
    }

    override fun consume(code: String): String? {
        return redisTemplate.opsForValue().getAndDelete(key(code))
    }

    private fun key(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return KEY_PREFIX + digest
    }

    companion object {
        private const val KEY_PREFIX = "oauth:exchange:"
    }
}
