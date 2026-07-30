package com.didimlog.infra.auth

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
@Import(RedisRefreshTokenStore::class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Redis Refresh Token 저장소 통합 테스트")
class RedisRefreshTokenStoreIntegrationTest {

    @Autowired
    private lateinit var store: RedisRefreshTokenStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val tokensToClean = mutableSetOf<String>()
    private val studentIdsToClean = mutableSetOf<String>()

    @AfterEach
    fun cleanUp() {
        val tokenKeys = tokensToClean.map(::tokenKey)
        val studentKeys = studentIdsToClean.map(::studentKey)
        if (tokenKeys.isNotEmpty()) {
            redisTemplate.delete(tokenKeys)
        }
        if (studentKeys.isNotEmpty()) {
            redisTemplate.delete(studentKeys)
        }
        tokensToClean.clear()
        studentIdsToClean.clear()
    }

    @Test
    @DisplayName("같은 토큰의 동시 교체는 한 번만 성공하고 같은 학생의 다른 기기 토큰은 유지한다")
    fun `concurrent rotation succeeds once`() {
        val suffix = UUID.randomUUID().toString()
        val studentId = "student-$suffix"
        val oldToken = "old-$suffix"
        val siblingToken = "sibling-$suffix"
        val candidateTokens = List(CONCURRENCY) { index -> "new-$index-$suffix" }
        track(studentId, listOf(oldToken, siblingToken) + candidateTokens)

        store.save(oldToken, studentId, TTL_SECONDS)
        store.save(siblingToken, studentId, TTL_SECONDS)

        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        val results = try {
            val futures = candidateTokens.map { candidateToken ->
                executor.submit<Pair<String, Boolean>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    candidateToken to store.rotate(
                        oldToken = oldToken,
                        newToken = candidateToken,
                        studentId = studentId,
                        ttlSeconds = TTL_SECONDS
                    )
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

        assertThat(results.count { (_, rotated) -> rotated }).isEqualTo(1)
        val winner = results.single { (_, rotated) -> rotated }.first
        val losers = results.filterNot { (_, rotated) -> rotated }.map { (token) -> token }

        assertThat(redisTemplate.opsForValue().get(tokenKey(oldToken))).isNull()
        assertThat(redisTemplate.opsForValue().get(tokenKey(winner))).isEqualTo(studentId)
        assertThat(redisTemplate.opsForValue().get(tokenKey(siblingToken))).isEqualTo(studentId)
        assertThat(losers).allSatisfy { token ->
            assertThat(redisTemplate.opsForValue().get(tokenKey(token))).isNull()
        }
        assertThat(redisTemplate.opsForSet().members(studentKey(studentId)))
            .containsExactlyInAnyOrder(siblingToken, winner)
        assertTtl(tokenKey(winner))
        assertTtl(tokenKey(siblingToken))
        assertTtl(studentKey(studentId))

        store.deleteByStudentId(studentId)

        assertThat(redisTemplate.opsForValue().get(tokenKey(siblingToken))).isNull()
        assertThat(redisTemplate.opsForValue().get(tokenKey(winner))).isNull()
        assertThat(redisTemplate.hasKey(studentKey(studentId))).isFalse()
    }

    @Test
    @DisplayName("서명된 학생 ID와 저장된 소유자가 다르면 아무 키도 변경하지 않는다")
    fun `student owner mismatch does not mutate tokens`() {
        val suffix = UUID.randomUUID().toString()
        val studentId = "owner-$suffix"
        val otherStudentId = "other-$suffix"
        val oldToken = "old-$suffix"
        val newToken = "new-$suffix"
        track(studentId, listOf(oldToken, newToken))
        track(otherStudentId, emptyList())

        store.save(oldToken, studentId, TTL_SECONDS)

        val rotated = store.rotate(
            oldToken = oldToken,
            newToken = newToken,
            studentId = otherStudentId,
            ttlSeconds = TTL_SECONDS
        )

        assertThat(rotated).isFalse()
        assertThat(redisTemplate.opsForValue().get(tokenKey(oldToken))).isEqualTo(studentId)
        assertThat(redisTemplate.opsForValue().get(tokenKey(newToken))).isNull()
        assertThat(redisTemplate.opsForSet().members(studentKey(studentId))).containsExactly(oldToken)
        assertThat(redisTemplate.hasKey(studentKey(otherStudentId))).isFalse()
    }

    @Test
    @DisplayName("새 토큰 키가 이미 있으면 기존 매핑을 덮어쓰지 않는다")
    fun `new token collision does not mutate tokens`() {
        val suffix = UUID.randomUUID().toString()
        val studentId = "owner-$suffix"
        val otherStudentId = "other-$suffix"
        val oldToken = "old-$suffix"
        val existingNewToken = "existing-new-$suffix"
        track(studentId, listOf(oldToken, existingNewToken))
        track(otherStudentId, emptyList())

        store.save(oldToken, studentId, TTL_SECONDS)
        store.save(existingNewToken, otherStudentId, TTL_SECONDS)

        val rotated = store.rotate(
            oldToken = oldToken,
            newToken = existingNewToken,
            studentId = studentId,
            ttlSeconds = TTL_SECONDS
        )

        assertThat(rotated).isFalse()
        assertThat(redisTemplate.opsForValue().get(tokenKey(oldToken))).isEqualTo(studentId)
        assertThat(redisTemplate.opsForValue().get(tokenKey(existingNewToken))).isEqualTo(otherStudentId)
        assertThat(redisTemplate.opsForSet().members(studentKey(studentId))).containsExactly(oldToken)
        assertThat(redisTemplate.opsForSet().members(studentKey(otherStudentId)))
            .containsExactly(existingNewToken)
    }

    @Test
    @DisplayName("토큰 교체와 전체 폐기가 겹쳐도 학생 토큰은 남지 않는다")
    fun `rotation and revoke all leave no token`() {
        repeat(RACE_REPETITIONS) { iteration ->
            val suffix = "$iteration-${UUID.randomUUID()}"
            val studentId = "revoke-$suffix"
            val oldToken = "old-$suffix"
            val newToken = "new-$suffix"
            track(studentId, listOf(oldToken, newToken))
            store.save(oldToken, studentId, TTL_SECONDS)

            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            try {
                val rotateFuture = executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    store.rotate(oldToken, newToken, studentId, TTL_SECONDS)
                }
                val revokeFuture = executor.submit<Unit> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    store.deleteByStudentId(studentId)
                }

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
                start.countDown()
                rotateFuture.get(10, TimeUnit.SECONDS)
                revokeFuture.get(10, TimeUnit.SECONDS)
            } finally {
                start.countDown()
                executor.shutdownNow()
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
            }

            assertThat(redisTemplate.opsForValue().get(tokenKey(oldToken))).isNull()
            assertThat(redisTemplate.opsForValue().get(tokenKey(newToken))).isNull()
            assertThat(redisTemplate.hasKey(studentKey(studentId))).isFalse()
        }
    }

    @Test
    @DisplayName("새 네임스페이스는 기존 BOJ ID 기반 소유자 집합을 건드리지 않는다")
    fun `legacy owner namespace is left to expire`() {
        val suffix = UUID.randomUUID().toString()
        val studentId = "student-$suffix"
        val token = "token-$suffix"
        val legacyKey = "refresh:user:foo-$suffix"
        track(studentId, listOf(token))
        redisTemplate.opsForSet().add(legacyKey, "legacy-token")
        redisTemplate.expire(legacyKey, TTL_SECONDS, TimeUnit.SECONDS)

        try {
            store.save(token, studentId, TTL_SECONDS)
            store.deleteByStudentId(studentId)

            assertThat(redisTemplate.hasKey(legacyKey)).isTrue()
            assertThat(redisTemplate.opsForSet().members(legacyKey)).containsExactly("legacy-token")
        } finally {
            redisTemplate.delete(legacyKey)
        }
    }

    private fun track(studentId: String, tokens: Collection<String>) {
        studentIdsToClean += studentId
        tokensToClean += tokens
    }

    private fun tokenKey(token: String): String = "refresh:token:$token"

    private fun studentKey(studentId: String): String = "refresh:student:$studentId"

    private fun assertTtl(key: String) {
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
            .isBetween(1L, TTL_SECONDS)
    }

    companion object {
        private const val TTL_SECONDS = 300L
        private const val CONCURRENCY = 20
        private const val RACE_REPETITIONS = 20
    }
}
