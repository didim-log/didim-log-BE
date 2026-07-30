package com.didimlog.infra.auth

import com.didimlog.application.auth.oauth.OAuthExchangeCodeStore
import com.didimlog.application.auth.oauth.OAuthExchangeCodeIdentity
import com.didimlog.domain.enums.Role
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisOAuthExchangeCodeStore(
    private val redisTemplate: StringRedisTemplate
) : OAuthExchangeCodeStore {

    override fun save(
        code: String,
        identity: OAuthExchangeCodeIdentity,
        ttlSeconds: Long
    ): Boolean {
        return redisTemplate.opsForValue().setIfAbsent(
            key(code),
            serialize(identity),
            Duration.ofSeconds(ttlSeconds)
        ) == true
    }

    override fun consume(code: String): OAuthExchangeCodeIdentity? {
        val storedValue = redisTemplate.opsForValue().getAndDelete(key(code)) ?: return null
        return deserialize(storedValue)
    }

    private fun key(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return KEY_PREFIX + digest
    }

    private fun serialize(identity: OAuthExchangeCodeIdentity): String {
        val encodedStudentId = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(identity.studentId.toByteArray(StandardCharsets.UTF_8))
        val encodedBojId = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(identity.bojId.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            PAYLOAD_VERSION,
            encodedStudentId,
            encodedBojId,
            identity.credentialVersion.toString(),
            identity.role.value
        ).joinToString(PAYLOAD_SEPARATOR)
    }

    private fun deserialize(storedValue: String): OAuthExchangeCodeIdentity? {
        val parts = storedValue.split(PAYLOAD_SEPARATOR)
        if (parts.size != PAYLOAD_PART_COUNT || parts[0] != PAYLOAD_VERSION) {
            return null
        }

        return try {
            val studentId = String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8
            )
            val bojId = String(
                Base64.getUrlDecoder().decode(parts[2]),
                StandardCharsets.UTF_8
            )
            val credentialVersion = parts[3].toLong()
            val role = Role.entries.firstOrNull { it.value == parts[4] }
                ?: return null
            OAuthExchangeCodeIdentity(studentId, bojId, credentialVersion, role)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val KEY_PREFIX = "oauth:exchange:"
        private const val PAYLOAD_VERSION = "v2"
        private const val PAYLOAD_SEPARATOR = ":"
        private const val PAYLOAD_PART_COUNT = 5
    }
}
