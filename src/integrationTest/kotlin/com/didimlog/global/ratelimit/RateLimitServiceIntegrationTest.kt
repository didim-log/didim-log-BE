package com.didimlog.global.ratelimit

import java.time.Duration
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
@Import(RateLimitService::class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Rate Limit 서비스 통합 테스트")
class RateLimitServiceIntegrationTest {

    @Autowired
    private lateinit var service: RateLimitService

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
    @DisplayName("한도까지만 기록하고 초과 요청은 카운터를 늘리지 않는다")
    fun `records requests only up to the limit`() {
        val key = newKey("exact-limit")

        val allowed = List(3) {
            service.checkAndRecord(key, maxRequests = 3, windowMinutes = 1)
        }
        val blocked = service.checkAndRecord(key, maxRequests = 3, windowMinutes = 1)

        assertThat(allowed.map(RateLimitDecision::remainingRequests))
            .containsExactly(2, 1, 0)
        assertThat(allowed).allMatch { it.allowed }
        assertThat(blocked.allowed).isFalse()
        assertThat(blocked.remainingRequests).isZero()
        assertThat(blocked.retryAfterSeconds).isBetween(1L, 60L)
        assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("3")
    }

    @Test
    @DisplayName("20개 요청이 동시에 도착해도 제한이 1이면 한 요청만 허용한다")
    fun `concurrent requests allow exactly one`() {
        val key = newKey("concurrent")

        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        val decisions = try {
            val futures = List(CONCURRENCY) {
                executor.submit<RateLimitDecision> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    service.checkAndRecord(key, maxRequests = 1, windowMinutes = 1)
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

        assertThat(decisions.count(RateLimitDecision::allowed)).isEqualTo(1)
        assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("1")
    }

    @Test
    @DisplayName("한도 직전의 같은 키에 동시 요청이 반복돼도 매번 한 요청만 허용한다")
    fun `concurrent boundary allows one request in every round`() {
        val services = List(4) { RateLimitService(redisTemplate) }
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            repeat(5) { round ->
                val key = newKey("boundary-$round")
                redisTemplate.opsForValue().set(redisKey(key), "9", Duration.ofMinutes(1))
                val ready = CountDownLatch(CONCURRENCY)
                val start = CountDownLatch(1)
                val futures = List(CONCURRENCY) { index ->
                    executor.submit<RateLimitDecision> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        services[index % services.size].checkAndRecord(
                            key,
                            maxRequests = 10,
                            windowMinutes = 1
                        )
                    }
                }

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
                start.countDown()
                val decisions = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertThat(decisions.count(RateLimitDecision::allowed)).isEqualTo(1)
                assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("10")
            }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("허용되거나 차단된 후속 요청은 첫 요청의 만료 시간을 연장하지 않는다")
    fun `subsequent requests do not extend fixed window`() {
        val key = newKey("fixed-window")

        service.checkAndRecord(key, maxRequests = 2, windowMinutes = 1)
        redisTemplate.expire(redisKey(key), 10, TimeUnit.SECONDS)

        service.checkAndRecord(key, maxRequests = 2, windowMinutes = 1)
        service.checkAndRecord(key, maxRequests = 2, windowMinutes = 1)
        val ttlMillis = redisTemplate.getExpire(redisKey(key), TimeUnit.MILLISECONDS)

        assertThat(ttlMillis).isBetween(1L, TimeUnit.SECONDS.toMillis(10))
    }

    @Test
    @DisplayName("TTL이 없는 기존 카운터에는 고정 시간 구간을 복구한다")
    fun `repairs legacy key without ttl`() {
        val key = newKey("legacy")
        redisTemplate.opsForValue().set(redisKey(key), "1")

        val decision = service.checkAndRecord(key, maxRequests = 2, windowMinutes = 1)

        assertThat(decision.allowed).isTrue()
        assertThat(decision.remainingRequests).isZero()
        assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("2")
        assertThat(redisTemplate.getExpire(redisKey(key), TimeUnit.MILLISECONDS))
            .isBetween(1L, TimeUnit.MINUTES.toMillis(1))
    }

    @Test
    @DisplayName("한도에 도달한 TTL 없는 기존 카운터도 차단 상태로 만료 시간을 복구한다")
    fun `repairs blocked legacy key without ttl`() {
        val key = newKey("blocked-legacy")
        redisTemplate.opsForValue().set(redisKey(key), "2")

        val decision = service.checkAndRecord(key, maxRequests = 2, windowMinutes = 1)

        assertThat(decision.allowed).isFalse()
        assertThat(decision.remainingRequests).isZero()
        assertThat(decision.retryAfterSeconds).isBetween(1L, 60L)
        assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("2")
        assertThat(redisTemplate.getExpire(redisKey(key), TimeUnit.MILLISECONDS))
            .isBetween(1L, TimeUnit.MINUTES.toMillis(1))
    }

    @Test
    @DisplayName("값이 0인 기존 카운터의 남은 고정 구간을 연장하지 않는다")
    fun `does not extend an existing zero count window`() {
        val key = newKey("zero-count")
        redisTemplate.opsForValue().set(redisKey(key), "0", Duration.ofSeconds(10))

        val decision = service.checkAndRecord(key, maxRequests = 2, windowMinutes = 1)
        val ttlMillis = redisTemplate.getExpire(redisKey(key), TimeUnit.MILLISECONDS)

        assertThat(decision.allowed).isTrue()
        assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("1")
        assertThat(ttlMillis).isBetween(1L, TimeUnit.SECONDS.toMillis(10))
    }

    @Test
    @DisplayName("고정 구간이 만료되면 다음 요청을 다시 허용한다")
    fun `allows a request after the window expires`() {
        val key = newKey("expired")
        redisTemplate.opsForValue().set(redisKey(key), "1", Duration.ofMillis(150))

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (redisTemplate.hasKey(redisKey(key)) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10)
        }

        assertThat(redisTemplate.hasKey(redisKey(key))).isFalse()
        val decision = service.checkAndRecord(key, maxRequests = 1, windowMinutes = 1)
        assertThat(decision.allowed).isTrue()
        assertThat(decision.remainingRequests).isZero()
        assertThat(redisTemplate.opsForValue().get(redisKey(key))).isEqualTo("1")
    }

    private fun newKey(label: String): String {
        val key = "$label:${UUID.randomUUID()}"
        keysToClean += redisKey(key)
        return key
    }

    private fun redisKey(key: String): String = "rate_limit:$key"

    companion object {
        private const val CONCURRENCY = 20
    }
}
