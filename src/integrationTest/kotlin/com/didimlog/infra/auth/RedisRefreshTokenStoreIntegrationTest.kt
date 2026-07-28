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
    private val bojIdsToClean = mutableSetOf<String>()

    @AfterEach
    fun cleanUp() {
        val tokenKeys = tokensToClean.map(::tokenKey)
        val userKeys = bojIdsToClean.map(::userKey)
        if (tokenKeys.isNotEmpty()) {
            redisTemplate.delete(tokenKeys)
        }
        if (userKeys.isNotEmpty()) {
            redisTemplate.delete(userKeys)
        }
        tokensToClean.clear()
        bojIdsToClean.clear()
    }

    @Test
    @DisplayName("같은 토큰의 동시 교체는 한 번만 성공하고 다른 기기 토큰은 유지한다")
    fun `concurrent rotation succeeds once`() {
        val suffix = UUID.randomUUID().toString()
        val bojId = "rotation-$suffix"
        val oldToken = "old-$suffix"
        val siblingToken = "sibling-$suffix"
        val candidateTokens = List(CONCURRENCY) { index -> "new-$index-$suffix" }
        track(bojId, listOf(oldToken, siblingToken) + candidateTokens)

        store.save(oldToken, bojId, TTL_SECONDS)
        store.save(siblingToken, bojId, TTL_SECONDS)

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
                        bojId = bojId,
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
        assertThat(redisTemplate.opsForValue().get(tokenKey(winner))).isEqualTo(bojId)
        assertThat(redisTemplate.opsForValue().get(tokenKey(siblingToken))).isEqualTo(bojId)
        assertThat(losers).allSatisfy { token ->
            assertThat(redisTemplate.opsForValue().get(tokenKey(token))).isNull()
        }
        assertThat(redisTemplate.opsForSet().members(userKey(bojId)))
            .containsExactlyInAnyOrder(siblingToken, winner)
        assertTtl(tokenKey(winner))
        assertTtl(tokenKey(siblingToken))
        assertTtl(userKey(bojId))

        store.deleteByBojId(bojId)

        assertThat(redisTemplate.opsForValue().get(tokenKey(siblingToken))).isNull()
        assertThat(redisTemplate.opsForValue().get(tokenKey(winner))).isNull()
        assertThat(redisTemplate.hasKey(userKey(bojId))).isFalse()
    }

    @Test
    @DisplayName("서명 subject와 저장된 BOJ ID가 다르면 아무 키도 변경하지 않는다")
    fun `subject mismatch does not mutate tokens`() {
        val suffix = UUID.randomUUID().toString()
        val bojId = "owner-$suffix"
        val otherBojId = "other-$suffix"
        val oldToken = "old-$suffix"
        val newToken = "new-$suffix"
        track(bojId, listOf(oldToken, newToken))
        track(otherBojId, emptyList())

        store.save(oldToken, bojId, TTL_SECONDS)

        val rotated = store.rotate(
            oldToken = oldToken,
            newToken = newToken,
            bojId = otherBojId,
            ttlSeconds = TTL_SECONDS
        )

        assertThat(rotated).isFalse()
        assertThat(redisTemplate.opsForValue().get(tokenKey(oldToken))).isEqualTo(bojId)
        assertThat(redisTemplate.opsForValue().get(tokenKey(newToken))).isNull()
        assertThat(redisTemplate.opsForSet().members(userKey(bojId))).containsExactly(oldToken)
        assertThat(redisTemplate.hasKey(userKey(otherBojId))).isFalse()
    }

    @Test
    @DisplayName("새 토큰 키가 이미 있으면 기존 매핑을 덮어쓰지 않는다")
    fun `new token collision does not mutate tokens`() {
        val suffix = UUID.randomUUID().toString()
        val bojId = "owner-$suffix"
        val otherBojId = "other-$suffix"
        val oldToken = "old-$suffix"
        val existingNewToken = "existing-new-$suffix"
        track(bojId, listOf(oldToken, existingNewToken))
        track(otherBojId, emptyList())

        store.save(oldToken, bojId, TTL_SECONDS)
        store.save(existingNewToken, otherBojId, TTL_SECONDS)

        val rotated = store.rotate(
            oldToken = oldToken,
            newToken = existingNewToken,
            bojId = bojId,
            ttlSeconds = TTL_SECONDS
        )

        assertThat(rotated).isFalse()
        assertThat(redisTemplate.opsForValue().get(tokenKey(oldToken))).isEqualTo(bojId)
        assertThat(redisTemplate.opsForValue().get(tokenKey(existingNewToken))).isEqualTo(otherBojId)
        assertThat(redisTemplate.opsForSet().members(userKey(bojId))).containsExactly(oldToken)
        assertThat(redisTemplate.opsForSet().members(userKey(otherBojId))).containsExactly(existingNewToken)
    }

    @Test
    @DisplayName("토큰 교체와 전체 폐기가 겹쳐도 사용자 토큰은 남지 않는다")
    fun `rotation and revoke all leave no token`() {
        repeat(RACE_REPETITIONS) { iteration ->
            val suffix = "$iteration-${UUID.randomUUID()}"
            val bojId = "revoke-$suffix"
            val oldToken = "old-$suffix"
            val newToken = "new-$suffix"
            track(bojId, listOf(oldToken, newToken))
            store.save(oldToken, bojId, TTL_SECONDS)

            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            try {
                val rotateFuture = executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    store.rotate(oldToken, newToken, bojId, TTL_SECONDS)
                }
                val revokeFuture = executor.submit<Unit> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    store.deleteByBojId(bojId)
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
            assertThat(redisTemplate.hasKey(userKey(bojId))).isFalse()
        }
    }

    private fun track(bojId: String, tokens: Collection<String>) {
        bojIdsToClean += bojId
        tokensToClean += tokens
    }

    private fun tokenKey(token: String): String = "refresh:token:$token"

    private fun userKey(bojId: String): String = "refresh:user:$bojId"

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
