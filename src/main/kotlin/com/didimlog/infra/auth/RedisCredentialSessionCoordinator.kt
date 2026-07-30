package com.didimlog.infra.auth

import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class RedisCredentialSessionCoordinator(
    private val redisTemplate: StringRedisTemplate,
    @Qualifier(CREDENTIAL_SESSION_RENEWAL_EXECUTOR)
    private val renewalExecutor: ScheduledExecutorService
) : CredentialSessionCoordinator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun <T> execute(studentId: String, action: () -> T): T {
        return executeUnderLock(studentId, verifyAtCompletion = false, action)
    }

    override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
        return executeUnderLock(studentId, verifyAtCompletion = true, action)
    }

    private fun <T> executeUnderLock(
        studentId: String,
        verifyAtCompletion: Boolean,
        action: () -> T
    ): T {
        require(studentId.isNotBlank()) { "학생 ID는 필수입니다." }

        val key = "$LOCK_KEY_PREFIX$studentId"
        val owner = UUID.randomUUID().toString()
        val acquired = try {
            redisTemplate.opsForValue().setIfAbsent(key, owner, LOCK_TTL)
        } catch (exception: RuntimeException) {
            throw BusinessException(ErrorCode.SESSION_STATE_UNAVAILABLE).also {
                it.initCause(exception)
            }
        }

        if (acquired != true) {
            throw BusinessException(ErrorCode.SESSION_STATE_CONFLICT)
        }

        val renewal = try {
            renewalExecutor.scheduleAtFixedRate(
                { renew(key, owner) },
                RENEW_INTERVAL.toMillis(),
                RENEW_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS
            )
        } catch (exception: RuntimeException) {
            release(key, owner)
            throw BusinessException(ErrorCode.SESSION_STATE_UNAVAILABLE).also {
                it.initCause(exception)
            }
        }

        try {
            val result = action()
            if (verifyAtCompletion) {
                verifyOwnership(key, owner)
            }
            return result
        } finally {
            renewal.cancel(false)
            release(key, owner)
        }
    }

    private fun verifyOwnership(key: String, owner: String) {
        val renewed = try {
            redisTemplate.execute(
                RENEW_SCRIPT,
                listOf(key),
                owner,
                LOCK_TTL.toMillis().toString()
            )
        } catch (exception: RuntimeException) {
            throw BusinessException(ErrorCode.SESSION_STATE_UNAVAILABLE).also {
                it.initCause(exception)
            }
        }

        if (renewed != 1L) {
            throw BusinessException(ErrorCode.SESSION_STATE_CONFLICT)
        }
    }

    private fun renew(key: String, owner: String) {
        try {
            val renewed = redisTemplate.execute(
                RENEW_SCRIPT,
                listOf(key),
                owner,
                LOCK_TTL.toMillis().toString()
            )
            if (renewed != 1L) {
                log.error("자격 증명 세션 잠금 갱신 실패: key={}", key)
            }
        } catch (exception: RuntimeException) {
            log.error("자격 증명 세션 잠금 갱신 중 Redis 오류: key={}", key, exception)
        }
    }

    private fun release(key: String, owner: String) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, listOf(key), owner)
        } catch (exception: RuntimeException) {
            log.error("자격 증명 세션 잠금 해제 실패: key={}", key, exception)
        }
    }

    companion object {
        private const val LOCK_KEY_PREFIX = "credential:session:lock:"
        private val LOCK_TTL = Duration.ofSeconds(30)
        private val RENEW_INTERVAL = Duration.ofSeconds(10)
        private val RENEW_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """.trimIndent(),
            Long::class.java
        )
        private val RELEASE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.java
        )
    }
}
