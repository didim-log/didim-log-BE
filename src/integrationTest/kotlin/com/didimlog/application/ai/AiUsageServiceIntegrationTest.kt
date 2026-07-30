package com.didimlog.application.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}"
    ]
)
@Import(AiUsageService::class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("AI 사용량 서비스 통합 테스트")
class AiUsageServiceIntegrationTest {

    @Autowired
    private lateinit var service: AiUsageService

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private lateinit var testDate: String
    private lateinit var globalUsageKey: String
    private lateinit var fixedKeySnapshots: Map<String, RedisValueSnapshot>
    private val userUsageKeys = mutableSetOf<String>()
    private val reservationKeyPatterns = mutableSetOf<String>()
    private val reservations = ConcurrentLinkedQueue<AiUsageService.UsageReservation>()

    @BeforeEach
    fun setUp() {
        testDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        globalUsageKey = "AI_USAGE:GLOBAL:$testDate"
        fixedKeySnapshots = FIXED_CONFIG_KEYS.plus(globalUsageKey)
            .associateWith(::snapshot)
    }

    @AfterEach
    fun cleanUp() {
        reservations.forEach { reservation ->
            runCatching { service.releaseUsage(reservation) }
        }
        val danglingReservationKeys = reservationKeyPatterns
            .flatMap { pattern -> redisTemplate.keys(pattern) }
        if (danglingReservationKeys.isNotEmpty()) {
            redisTemplate.delete(danglingReservationKeys)
        }
        reservations.clear()
        reservationKeyPatterns.clear()

        if (userUsageKeys.isNotEmpty()) {
            redisTemplate.delete(userUsageKeys)
        }
        userUsageKeys.clear()

        redisTemplate.delete(fixedKeySnapshots.keys)
        fixedKeySnapshots.forEach { (key, snapshot) ->
            restore(key, snapshot)
        }
    }

    @Test
    @DisplayName("같은 사용자의 동시 요청은 남은 사용자 한도만 예약한다")
    fun `concurrent requests reserve only the remaining user limit`() {
        val userId = newUserId("user-limit")
        service.setServiceEnabled(true)
        service.updateLimits(globalLimit = 100, userLimit = 5)
        setUserUsage(userId, 4)
        redisTemplate.delete(globalUsageKey)

        val outcomes = reserveConcurrently(List(CONCURRENCY) { userId })

        assertThat(outcomes.count { it.reservation != null }).isEqualTo(1)
        assertThat(outcomes.mapNotNull(ReservationOutcome::errorCode))
            .containsOnly(ErrorCode.AI_USER_LIMIT_EXCEEDED)
            .hasSize(CONCURRENCY - 1)
        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isEqualTo("5")
        assertThat(redisTemplate.getExpire(globalUsageKey, TimeUnit.MILLISECONDS)).isGreaterThan(0L)
        assertThat(redisTemplate.getExpire(userUsageKey(userId), TimeUnit.MILLISECONDS)).isGreaterThan(0L)
    }

    @Test
    @DisplayName("서로 다른 사용자의 동시 요청은 남은 전역 한도만 예약한다")
    fun `concurrent users reserve only the remaining global limit`() {
        val userIds = List(CONCURRENCY) { index -> newUserId("global-limit-$index") }
        service.setServiceEnabled(true)
        service.updateLimits(globalLimit = 5, userLimit = 5)
        setGlobalUsage(4)

        val outcomes = reserveConcurrently(userIds)
        val successfulUserId = outcomes.single { it.reservation != null }.userId

        assertThat(outcomes.mapNotNull(ReservationOutcome::errorCode))
            .containsOnly(ErrorCode.AI_GLOBAL_LIMIT_EXCEEDED)
            .hasSize(CONCURRENCY - 1)
        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isEqualTo("5")
        userIds.forEach { userId ->
            val expectedUsage = if (userId == successfulUserId) "1" else null
            assertThat(redisTemplate.opsForValue().get(userUsageKey(userId)))
                .isEqualTo(expectedUsage)
        }
    }

    @Test
    @DisplayName("예약 해제는 한 번만 카운터를 되돌린다")
    fun `release is idempotent`() {
        val userId = newUserId("release")
        service.setServiceEnabled(true)
        service.updateLimits(globalLimit = 100, userLimit = 5)
        setGlobalUsage(2)
        setUserUsage(userId, 1)

        val reservation = service.reserveUsage(userId).also(reservations::add)

        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isEqualTo("3")
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isEqualTo("2")

        assertThat(service.releaseUsage(reservation)).isTrue()
        assertThat(service.releaseUsage(reservation)).isFalse()
        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isEqualTo("2")
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isEqualTo("1")
    }

    @Test
    @DisplayName("비활성 상태에서는 카운터를 만들지 않는다")
    fun `disabled service does not mutate usage`() {
        val userId = newUserId("disabled")
        service.setServiceEnabled(false)
        redisTemplate.delete(globalUsageKey)

        assertThatThrownBy { service.reserveUsage(userId) }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.AI_SERVICE_DISABLED }

        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isNull()
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isNull()
    }

    @Test
    @DisplayName("손상된 사용량 값은 예약 전에 0으로 정규화한다")
    fun `reservation normalizes a non integer counter`() {
        val userId = newUserId("invalid-reserve")
        service.setServiceEnabled(true)
        service.updateLimits(globalLimit = 100, userLimit = 5)
        redisTemplate.opsForValue().set(globalUsageKey, "1.5", Duration.ofHours(1))

        service.reserveUsage(userId).also(reservations::add)

        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isEqualTo("1")
    }

    @Test
    @DisplayName("손상된 카운터에서는 예약 키를 소비하지 않고 해제를 중단한다")
    fun `release keeps reservation when a counter is invalid`() {
        val userId = newUserId("invalid-release")
        service.setServiceEnabled(true)
        service.updateLimits(globalLimit = 100, userLimit = 5)
        val reservation = service.reserveUsage(userId).also(reservations::add)
        redisTemplate.opsForValue().set(globalUsageKey, "1.5", Duration.ofHours(1))

        assertThatThrownBy { service.releaseUsage(reservation) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("카운터")
        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isEqualTo("1.5")
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isEqualTo("1")

        redisTemplate.opsForValue().set(globalUsageKey, "1", Duration.ofHours(1))
        assertThat(service.releaseUsage(reservation)).isTrue()
        assertThat(redisTemplate.opsForValue().get(globalUsageKey)).isNull()
        assertThat(redisTemplate.opsForValue().get(userUsageKey(userId))).isNull()
    }

    private fun reserveConcurrently(userIds: List<String>): List<ReservationOutcome> {
        val services = List(4) { AiUsageService(redisTemplate) }
        val executor = Executors.newFixedThreadPool(userIds.size)
        val ready = CountDownLatch(userIds.size)
        val start = CountDownLatch(1)
        return try {
            val futures = userIds.mapIndexed { index, userId ->
                executor.submit<ReservationOutcome> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        val reservation = services[index % services.size].reserveUsage(userId)
                        reservations.add(reservation)
                        ReservationOutcome(userId = userId, reservation = reservation)
                    } catch (e: BusinessException) {
                        ReservationOutcome(userId = userId, errorCode = e.errorCode)
                    }
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
    }

    private fun newUserId(label: String): String {
        val userId = "phase5a-$label-${UUID.randomUUID()}"
        userUsageKeys += userUsageKey(userId)
        reservationKeyPatterns += "AI_USAGE:RESERVATION:$userId:$testDate:*"
        return userId
    }

    private fun setGlobalUsage(usage: Int) {
        redisTemplate.opsForValue().set(globalUsageKey, usage.toString(), Duration.ofHours(1))
    }

    private fun setUserUsage(userId: String, usage: Int) {
        redisTemplate.opsForValue().set(userUsageKey(userId), usage.toString(), Duration.ofHours(1))
    }

    private fun userUsageKey(userId: String): String = "AI_USAGE:USER:$userId:$testDate"

    private fun snapshot(key: String): RedisValueSnapshot {
        val value = redisTemplate.opsForValue().get(key)
        val ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)
        return RedisValueSnapshot(
            value = value,
            expiresAtMillis = if (ttlMillis > 0L) System.currentTimeMillis() + ttlMillis else null,
            persistent = ttlMillis == -1L
        )
    }

    private fun restore(key: String, snapshot: RedisValueSnapshot) {
        val value = snapshot.value ?: return
        if (snapshot.persistent) {
            redisTemplate.opsForValue().set(key, value)
            return
        }

        val remainingMillis = (snapshot.expiresAtMillis ?: return) - System.currentTimeMillis()
        if (remainingMillis > 0L) {
            redisTemplate.opsForValue().set(key, value, Duration.ofMillis(remainingMillis))
        }
    }

    private data class ReservationOutcome(
        val userId: String,
        val reservation: AiUsageService.UsageReservation? = null,
        val errorCode: ErrorCode? = null
    )

    private data class RedisValueSnapshot(
        val value: String?,
        val expiresAtMillis: Long?,
        val persistent: Boolean
    )

    companion object {
        private const val CONCURRENCY = 20
        private val FIXED_CONFIG_KEYS = setOf(
            "AI_CONFIG:LIMIT:GLOBAL",
            "AI_CONFIG:LIMIT:USER",
            "AI_SERVICE:ENABLED"
        )
    }
}
