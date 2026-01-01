package com.didimlog.infra.auth

import com.didimlog.application.auth.RefreshTokenStore
import java.time.Duration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis 기반 Refresh Token 저장소 구현
 */
@Component
class RedisRefreshTokenStore(
    private val redisTemplate: StringRedisTemplate
) : RefreshTokenStore {

    companion object {
        private const val TOKEN_KEY_PREFIX = "refresh:token:"
        private const val USER_KEY_PREFIX = "refresh:user:"
    }

    override fun save(token: String, bojId: String, ttlSeconds: Long) {
        val tokenKey = tokenKey(token)
        val userKey = userKey(bojId)
        
        // Token -> BojId 매핑 저장
        redisTemplate.opsForValue().set(tokenKey, bojId, Duration.ofSeconds(ttlSeconds))
        
        // User -> Token 매핑 저장 (사용자별 토큰 관리용)
        redisTemplate.opsForSet().add(userKey, token)
        redisTemplate.expire(userKey, Duration.ofSeconds(ttlSeconds))
    }

    override fun find(token: String): String? {
        return redisTemplate.opsForValue().get(tokenKey(token))
    }

    override fun delete(token: String) {
        val bojId = find(token)
        if (bojId != null) {
            val userKey = userKey(bojId)
            redisTemplate.opsForSet().remove(userKey, token)
        }
        redisTemplate.delete(tokenKey(token))
    }

    override fun deleteByBojId(bojId: String) {
        val userKey = userKey(bojId)
        val tokens = redisTemplate.opsForSet().members(userKey) ?: emptySet()
        
        tokens.forEach { token ->
            redisTemplate.delete(tokenKey(token))
        }
        
        redisTemplate.delete(userKey)
    }

    private fun tokenKey(token: String): String {
        return TOKEN_KEY_PREFIX + token
    }

    private fun userKey(bojId: String): String {
        return USER_KEY_PREFIX + bojId
    }
}

