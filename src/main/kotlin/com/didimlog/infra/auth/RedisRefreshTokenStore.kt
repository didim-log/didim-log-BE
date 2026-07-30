package com.didimlog.infra.auth

import com.didimlog.application.auth.RefreshTokenStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
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
        private const val STUDENT_KEY_PREFIX = "refresh:student:"

        private val SAVE_SCRIPT = DefaultRedisScript(
            """
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            redis.call('SADD', KEYS[2], ARGV[2])
            local currentTtl = redis.call('TTL', KEYS[2])
            local requestedTtl = tonumber(ARGV[3])
            if currentTtl < requestedTtl then
                redis.call('EXPIRE', KEYS[2], requestedTtl)
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )

        private val ROTATE_SCRIPT = DefaultRedisScript(
            """
            local storedStudentId = redis.call('GET', KEYS[1])
            if not storedStudentId then
                return 0
            end
            if storedStudentId ~= ARGV[1] then
                return -1
            end
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return -2
            end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[3], ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[4])
            redis.call('SADD', KEYS[3], ARGV[3])
            local currentTtl = redis.call('TTL', KEYS[3])
            local requestedTtl = tonumber(ARGV[4])
            if currentTtl < requestedTtl then
                redis.call('EXPIRE', KEYS[3], requestedTtl)
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )

        private val DELETE_BY_STUDENT_ID_SCRIPT = DefaultRedisScript(
            """
            local tokens = redis.call('SMEMBERS', KEYS[1])
            for _, token in ipairs(tokens) do
                redis.call('DEL', ARGV[1] .. token)
            end
            redis.call('DEL', KEYS[1])
            return #tokens
            """.trimIndent(),
            Long::class.java
        )
    }

    override fun save(token: String, studentId: String, ttlSeconds: Long) {
        redisTemplate.execute(
            SAVE_SCRIPT,
            listOf(tokenKey(token), studentKey(studentId)),
            studentId,
            token,
            ttlSeconds.toString()
        )
    }

    override fun matches(token: String, studentId: String): Boolean {
        return redisTemplate.opsForValue().get(tokenKey(token)) == studentId
    }

    override fun rotate(
        oldToken: String,
        newToken: String,
        studentId: String,
        ttlSeconds: Long
    ): Boolean {
        val result = redisTemplate.execute(
            ROTATE_SCRIPT,
            listOf(tokenKey(oldToken), tokenKey(newToken), studentKey(studentId)),
            studentId,
            oldToken,
            newToken,
            ttlSeconds.toString()
        )
        return result == 1L
    }

    override fun deleteByStudentId(studentId: String) {
        redisTemplate.execute(
            DELETE_BY_STUDENT_ID_SCRIPT,
            listOf(studentKey(studentId)),
            TOKEN_KEY_PREFIX
        )
    }

    private fun tokenKey(token: String): String {
        return TOKEN_KEY_PREFIX + token
    }

    private fun studentKey(studentId: String): String {
        return STUDENT_KEY_PREFIX + studentId
    }
}










