package com.didimlog.infra.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=14"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Gemini 호출 제한 통합 테스트")
class GeminiRateLimiterIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun setUp() {
        deleteRateLimitKeys()
    }

    @AfterEach
    fun cleanUp() {
        deleteRateLimitKeys()
    }

    @Test
    @DisplayName("동시에 도착한 요청 중 최소 간격은 한 요청만 허용한다")
    fun `minimum interval allows one concurrent request`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val policy = GeminiRateLimitProperties(
            minIntervalSeconds = 4,
            maxRpm = 100,
            maxRpd = 100
        )
        val allowed = runConcurrently(
            limiters = List(4) { createLimiter(policy, now) }
        )
        val keys = keysAt(now)

        assertThat(allowed).isEqualTo(1)
        assertThat(redisTemplate.opsForValue().get(keys.rpm)).isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("1")
        assertThat(
            redisTemplate.getExpire(LAST_REQUEST_KEY, TimeUnit.MILLISECONDS)
        ).isBetween(1L, Duration.ofSeconds(4).toMillis())
    }

    @Test
    @DisplayName("RPM 한도 직전의 동시 요청은 한 건만 기록한다")
    fun `rpm boundary records one concurrent request`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val keys = keysAt(now)
        redisTemplate.opsForValue().set(
            keys.rpm,
            "14",
            Duration.ofSeconds(30)
        )
        redisTemplate.opsForValue().set(
            keys.rpd,
            "7",
            Duration.ofMinutes(30)
        )
        val policy = GeminiRateLimitProperties(
            minIntervalSeconds = 0,
            maxRpm = 15,
            maxRpd = 1_500
        )

        val allowed = runConcurrently(
            limiters = List(4) { createLimiter(policy, now) }
        )

        assertThat(allowed).isEqualTo(1)
        assertThat(redisTemplate.opsForValue().get(keys.rpm)).isEqualTo("15")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("8")
        assertThat(redisTemplate.hasKey(LAST_REQUEST_KEY)).isFalse()
    }

    @Test
    @DisplayName("RPD 차단은 RPM과 RPD를 증가시키거나 TTL을 연장하지 않는다")
    fun `rpd rejection does not mutate counters`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val keys = keysAt(now)
        redisTemplate.opsForValue().set(
            keys.rpm,
            "3",
            Duration.ofSeconds(20)
        )
        redisTemplate.opsForValue().set(
            keys.rpd,
            "1500",
            Duration.ofSeconds(20)
        )
        val rpmTtlBefore = redisTemplate.getExpire(
            keys.rpm,
            TimeUnit.MILLISECONDS
        )
        val rpdTtlBefore = redisTemplate.getExpire(
            keys.rpd,
            TimeUnit.MILLISECONDS
        )
        val limiter = createLimiter(
            GeminiRateLimitProperties(
                minIntervalSeconds = 0,
                maxRpm = 15,
                maxRpd = 1_500
            ),
            now
        )

        assertBusy { limiter.checkAndIncrement() }

        assertThat(redisTemplate.opsForValue().get(keys.rpm)).isEqualTo("3")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("1500")
        assertThat(
            redisTemplate.getExpire(keys.rpm, TimeUnit.MILLISECONDS)
        ).isBetween(1L, rpmTtlBefore)
        assertThat(
            redisTemplate.getExpire(keys.rpd, TimeUnit.MILLISECONDS)
        ).isBetween(1L, rpdTtlBefore)
        assertThat(redisTemplate.hasKey(LAST_REQUEST_KEY)).isFalse()
    }

    @Test
    @DisplayName("최소 간격 차단은 카운터와 간격 TTL을 연장하지 않는다")
    fun `minimum interval rejection does not mutate usage`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val policy = GeminiRateLimitProperties(
            minIntervalSeconds = 4,
            maxRpm = 15,
            maxRpd = 1_500
        )
        createLimiter(policy, now).checkAndIncrement()
        val keys = keysAt(now)
        val intervalTtlBefore = redisTemplate.getExpire(
            LAST_REQUEST_KEY,
            TimeUnit.MILLISECONDS
        )

        assertBusy {
            createLimiter(policy, now.plusMillis(100)).checkAndIncrement()
        }

        assertThat(redisTemplate.opsForValue().get(keys.rpm)).isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("1")
        assertThat(
            redisTemplate.getExpire(LAST_REQUEST_KEY, TimeUnit.MILLISECONDS)
        ).isBetween(1L, intervalTtlBefore)
    }

    @Test
    @DisplayName("TTL 없는 기존 키를 남은 구간에 맞춰 전환한다")
    fun `migrates legacy keys without ttl`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val keys = keysAt(now)
        redisTemplate.opsForValue().set(keys.rpm, "2")
        redisTemplate.opsForValue().set(keys.rpd, "3")
        redisTemplate.opsForValue().set(
            LAST_REQUEST_KEY,
            now.minusSeconds(10).epochSecond.toString()
        )
        val limiter = createLimiter(AiGeminiProperties().rateLimit, now)

        limiter.checkAndIncrement()

        assertThat(redisTemplate.opsForValue().get(keys.rpm)).isEqualTo("3")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("4")
        assertThat(
            redisTemplate.getExpire(keys.rpm, TimeUnit.MILLISECONDS)
        ).isPositive()
        assertThat(
            redisTemplate.getExpire(keys.rpd, TimeUnit.MILLISECONDS)
        ).isPositive()
        assertThat(
            redisTemplate.getExpire(LAST_REQUEST_KEY, TimeUnit.MILLISECONDS)
        ).isBetween(1L, Duration.ofSeconds(4).toMillis())
    }

    @Test
    @DisplayName("최근의 TTL 없는 간격 키는 남은 시간만 복구하고 차단한다")
    fun `migrates active legacy interval without counting rejection`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val keys = keysAt(now)
        redisTemplate.opsForValue().set(keys.rpm, "2")
        redisTemplate.opsForValue().set(keys.rpd, "3")
        redisTemplate.opsForValue().set(
            LAST_REQUEST_KEY,
            now.minusSeconds(1).epochSecond.toString()
        )
        val limiter = createLimiter(AiGeminiProperties().rateLimit, now)

        assertBusy { limiter.checkAndIncrement() }

        assertThat(redisTemplate.opsForValue().get(keys.rpm)).isEqualTo("2")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("3")
        assertThat(
            redisTemplate.getExpire(LAST_REQUEST_KEY, TimeUnit.MILLISECONDS)
        ).isBetween(1L, Duration.ofSeconds(4).toMillis())
    }

    @Test
    @DisplayName("손상된 카운터는 사용량을 기록하지 않고 실패한다")
    fun `corrupted counter fails closed`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val keys = keysAt(now)
        redisTemplate.opsForValue().set(keys.rpm, "broken")
        redisTemplate.opsForValue().set(keys.rpd, "3")
        val limiter = createLimiter(AiGeminiProperties().rateLimit, now)

        assertThatThrownBy {
            limiter.checkAndIncrement()
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(redisTemplate.opsForValue().get(keys.rpm))
            .isEqualTo("broken")
        assertThat(redisTemplate.opsForValue().get(keys.rpd)).isEqualTo("3")
        assertThat(redisTemplate.hasKey(LAST_REQUEST_KEY)).isFalse()
    }

    @Test
    @DisplayName("음수 일일 사용량은 손상 상태로 처리한다")
    fun `negative daily usage fails closed`() {
        val now = Instant.parse("2026-01-01T00:00:30Z")
        val keys = keysAt(now)
        redisTemplate.opsForValue().set(keys.rpd, "-1")
        val limiter = createLimiter(AiGeminiProperties().rateLimit, now)

        assertThatThrownBy {
            limiter.getDailyUsage()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("UTC 분과 일 경계에서 새 버킷으로 전환한다")
    fun `uses new buckets at utc boundaries`() {
        val policy = GeminiRateLimitProperties(
            minIntervalSeconds = 0,
            maxRpm = 15,
            maxRpd = 1_500
        )
        val beforeMinute = Instant.parse("2026-01-01T00:00:55Z")
        val afterMinute = beforeMinute.plusSeconds(5)

        createLimiter(policy, beforeMinute).checkAndIncrement()
        createLimiter(policy, afterMinute).checkAndIncrement()

        val firstMinuteKeys = keysAt(beforeMinute)
        val secondMinuteKeys = keysAt(afterMinute)
        assertThat(firstMinuteKeys.rpm).isNotEqualTo(secondMinuteKeys.rpm)
        assertThat(redisTemplate.opsForValue().get(firstMinuteKeys.rpm))
            .isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(secondMinuteKeys.rpm))
            .isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(firstMinuteKeys.rpd))
            .isEqualTo("2")

        deleteRateLimitKeys()
        val beforeDay = Instant.parse("2026-01-01T23:59:55Z")
        val afterDay = beforeDay.plusSeconds(5)
        createLimiter(policy, beforeDay).checkAndIncrement()
        val afterDayLimiter = createLimiter(policy, afterDay)
        afterDayLimiter.checkAndIncrement()

        val firstDayKeys = keysAt(beforeDay)
        val secondDayKeys = keysAt(afterDay)
        assertThat(firstDayKeys.rpd).isNotEqualTo(secondDayKeys.rpd)
        assertThat(redisTemplate.opsForValue().get(firstDayKeys.rpd))
            .isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(secondDayKeys.rpd))
            .isEqualTo("1")
        assertThat(afterDayLimiter.getDailyUsage()).isEqualTo(1L)
    }

    private fun runConcurrently(
        limiters: List<GeminiRateLimiter>
    ): Int {
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        return try {
            val futures = List(CONCURRENCY) { index ->
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        limiters[index % limiters.size].checkAndIncrement()
                        true
                    } catch (e: BusinessException) {
                        if (e.errorCode != ErrorCode.AI_SERVICE_BUSY) {
                            throw e
                        }
                        false
                    }
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.count { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun createLimiter(
        policy: GeminiRateLimitProperties,
        now: Instant
    ): GeminiRateLimiter {
        return GeminiRateLimiter(
            redisTemplate,
            AiGeminiProperties(rateLimit = policy),
            Clock.fixed(now, ZoneOffset.UTC)
        )
    }

    private fun assertBusy(call: () -> Unit) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException::class.java)
            .matches {
                (it as BusinessException).errorCode == ErrorCode.AI_SERVICE_BUSY
            }
    }

    private fun keysAt(now: Instant): RateLimitKeys {
        val epochMillis = now.toEpochMilli()
        return RateLimitKeys(
            rpm = "$RPM_KEY_PREFIX${Math.floorDiv(epochMillis, MILLIS_PER_MINUTE)}",
            rpd = "$RPD_KEY_PREFIX${Math.floorDiv(epochMillis, MILLIS_PER_DAY)}"
        )
    }

    private fun deleteRateLimitKeys() {
        val keys = redisTemplate.keys("$KEY_PREFIX*")
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
        }
    }

    private data class RateLimitKeys(
        val rpm: String,
        val rpd: String
    )

    companion object {
        private const val KEY_PREFIX = "gemini:rate:"
        private const val RPM_KEY_PREFIX = "gemini:rate:rpm:"
        private const val RPD_KEY_PREFIX = "gemini:rate:rpd:"
        private const val LAST_REQUEST_KEY = "gemini:rate:last:"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val CONCURRENCY = 20
    }
}
