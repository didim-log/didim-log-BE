package com.didimlog.infra.auth

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}"
    ]
)
@Import(RedisOAuthExchangeCodeStore::class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Redis OAuth 교환 코드 저장소 통합 테스트")
class RedisOAuthExchangeCodeStoreIntegrationTest {

    @Autowired
    private lateinit var store: RedisOAuthExchangeCodeStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val keysToClean = mutableSetOf<String>()

    @AfterEach
    fun cleanUp() {
        if (keysToClean.isNotEmpty()) {
            redisTemplate.delete(keysToClean)
        }
        keysToClean.clear()
    }

    @Test
    @DisplayName("동시에 소비해도 한 번만 학생 ID를 반환하고 저장 TTL을 유지한다")
    fun `concurrent consume succeeds once and keeps ttl`() {
        val code = "oauth-${UUID.randomUUID()}"
        val studentId = "student-${UUID.randomUUID()}"
        val key = key(code)
        keysToClean += key

        assertThat(store.save(code, studentId, TTL_SECONDS)).isTrue()
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(studentId)
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
            .isBetween(1L, TTL_SECONDS)

        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        val results = try {
            val futures = List(CONCURRENCY) {
                executor.submit<String?> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    store.consume(code)
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(results.count { result -> result == studentId }).isEqualTo(1)
        assertThat(results.count { result -> result == null }).isEqualTo(CONCURRENCY - 1)
        assertThat(redisTemplate.hasKey(key)).isFalse()
    }

    private fun key(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "oauth:exchange:$digest"
    }

    companion object {
        private const val TTL_SECONDS = 90L
        private const val CONCURRENCY = 20
    }
}
