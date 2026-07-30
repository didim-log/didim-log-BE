package com.didimlog.global.system

import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
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
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=\${TEST_REDIS_MAINTENANCE_DATABASE:15}"
    ]
)
@Import(MaintenanceModeService::class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("유지보수 모드 서비스 통합 테스트")
class MaintenanceModeServiceIntegrationTest {

    @Autowired
    private lateinit var service: MaintenanceModeService

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun setUp() {
        redisTemplate.delete(MAINTENANCE_KEYS)
    }

    @AfterEach
    fun cleanUp() {
        redisTemplate.delete(MAINTENANCE_KEYS)
    }

    @Test
    @DisplayName("종료 시각이 지나면 설정 전체가 만료되고 비활성 상태가 된다")
    fun `expires the complete configuration at end time`() {
        val endTime = LocalDateTime.now().plusSeconds(3)
        service.setMaintenanceMode(
            enabled = true,
            startTime = LocalDateTime.now(),
            endTime = endTime,
            noticeId = "notice-expiring"
        )

        assertThat(redisTemplate.hasKey(CONFIG_KEY)).isTrue()
        assertThat(service.isMaintenanceMode()).isTrue()
        assertThat(
            redisTemplate.getExpire(CONFIG_KEY, TimeUnit.MILLISECONDS)
        ).isPositive()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (redisTemplate.hasKey(CONFIG_KEY) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }

        assertThat(redisTemplate.hasKey(CONFIG_KEY)).isFalse()
        assertThat(
            redisTemplate.getExpire(CONFIG_KEY, TimeUnit.MILLISECONDS)
        ).isEqualTo(-2L)
        assertThat(service.isMaintenanceMode()).isFalse()
        assertThat(service.getMaintenanceConfig())
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
    }

    @Test
    @DisplayName("활성 설정과 TTL은 같은 Redis 키에 저장된다")
    fun `stores complete configuration and ttl on one key`() {
        val startTime = LocalDateTime.now().plusMinutes(1)
        val endTime = LocalDateTime.now().plusSeconds(10)

        service.setMaintenanceMode(
            enabled = true,
            startTime = startTime,
            endTime = endTime,
            noticeId = "notice-atomic"
        )

        assertThat(redisTemplate.hasKey(CONFIG_KEY)).isTrue()
        assertThat(redisTemplate.hasKey("maintenance:enabled")).isFalse()
        assertThat(redisTemplate.hasKey("maintenance:startTime")).isFalse()
        assertThat(redisTemplate.hasKey("maintenance:endTime")).isFalse()
        assertThat(redisTemplate.hasKey("maintenance:noticeId")).isFalse()
        assertThat(
            redisTemplate.getExpire(CONFIG_KEY, TimeUnit.MILLISECONDS)
        ).isBetween(1L, TimeUnit.SECONDS.toMillis(10))
        assertThat(service.getMaintenanceConfig()).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                startTime = startTime,
                endTime = endTime,
                noticeId = "notice-atomic"
            )
        )
    }

    @Test
    @DisplayName("종료 시각 없는 설정은 TTL 없이 유지된다")
    fun `stores open ended configuration without ttl`() {
        service.setMaintenanceMode(
            enabled = true,
            startTime = LocalDateTime.now(),
            noticeId = "notice-open-ended"
        )

        assertThat(
            redisTemplate.getExpire(CONFIG_KEY, TimeUnit.MILLISECONDS)
        ).isEqualTo(-1L)
        assertThat(service.isMaintenanceMode()).isTrue()
    }

    @Test
    @DisplayName("유한 설정을 무기한 설정으로 덮어쓰면 기존 TTL을 제거한다")
    fun `open ended update removes previous ttl`() {
        service.setMaintenanceMode(
            enabled = true,
            endTime = LocalDateTime.now().plusMinutes(1),
            noticeId = "notice-finite"
        )
        assertThat(
            redisTemplate.getExpire(CONFIG_KEY, TimeUnit.MILLISECONDS)
        ).isPositive()

        service.setMaintenanceMode(
            enabled = true,
            noticeId = "notice-open-ended"
        )

        assertThat(
            redisTemplate.getExpire(CONFIG_KEY, TimeUnit.MILLISECONDS)
        ).isEqualTo(-1L)
        assertThat(service.getMaintenanceConfig()).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                noticeId = "notice-open-ended"
            )
        )
    }

    @Test
    @DisplayName("비활성화하면 유지보수 설정 키가 남지 않는다")
    fun `disable deletes the configuration key`() {
        service.setMaintenanceMode(
            enabled = true,
            noticeId = "notice-before-disable"
        )
        LEGACY_MAINTENANCE_KEYS.forEach {
            redisTemplate.opsForValue().set(it, "legacy")
        }

        service.setMaintenanceMode(false)

        assertThat(redisTemplate.keys("maintenance:*")).isEmpty()
        assertThat(service.getMaintenanceConfig())
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
    }

    @Test
    @DisplayName("새 설정이 없으면 기존 유지보수 설정을 읽는다")
    fun `reads legacy configuration before first new write`() {
        val startTime = LocalDateTime.now().minusMinutes(1)
        val endTime = LocalDateTime.now().plusMinutes(1)
        redisTemplate.opsForValue().set("maintenance:enabled", "true")
        redisTemplate.opsForValue().set(
            "maintenance:startTime",
            startTime.toString()
        )
        redisTemplate.opsForValue().set(
            "maintenance:endTime",
            endTime.toString(),
            java.time.Duration.ofMinutes(1)
        )
        redisTemplate.opsForValue().set(
            "maintenance:noticeId",
            "legacy-notice"
        )

        assertThat(service.getMaintenanceConfig()).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                startTime = startTime,
                endTime = endTime,
                noticeId = "legacy-notice"
            )
        )
    }

    @Test
    @DisplayName("새 설정을 저장하면 기존 설정이 다시 활성화되지 않는다")
    fun `new configuration replaces all legacy keys`() {
        LEGACY_MAINTENANCE_KEYS.forEach {
            redisTemplate.opsForValue().set(it, "legacy")
        }

        service.setMaintenanceMode(
            enabled = true,
            endTime = LocalDateTime.now().plusSeconds(3),
            noticeId = "new-notice"
        )

        assertThat(LEGACY_MAINTENANCE_KEYS)
            .allMatch { redisTemplate.hasKey(it) == false }
        assertThat(service.getMaintenanceConfig().noticeId)
            .isEqualTo("new-notice")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (redisTemplate.hasKey(CONFIG_KEY) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }

        assertThat(redisTemplate.hasKey(CONFIG_KEY)).isFalse()
        assertThat(service.isMaintenanceMode()).isFalse()
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 설정해도 서로 다른 설정이 섞이지 않는다")
    fun `concurrent writers expose only complete configurations`() {
        val first = MaintenanceModeService.MaintenanceConfig(
            enabled = true,
            startTime = LocalDateTime.of(2026, 1, 1, 10, 0),
            noticeId = "notice-first"
        )
        val second = MaintenanceModeService.MaintenanceConfig(
            enabled = true,
            startTime = LocalDateTime.of(2026, 2, 2, 11, 0),
            noticeId = "notice-second"
        )
        val firstService = MaintenanceModeService(redisTemplate)
        val secondService = MaintenanceModeService(redisTemplate)
        firstService.setMaintenanceMode(
            true,
            first.startTime,
            first.endTime,
            first.noticeId
        )
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val writersDone = CountDownLatch(2)
        val firstObserved = CountDownLatch(1)
        val secondObserved = CountDownLatch(1)
        val firstWriteCount = AtomicInteger()
        val secondWriteCount = AtomicInteger()
        val observed = ConcurrentLinkedQueue<
            MaintenanceModeService.MaintenanceConfig
        >()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = listOf(
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        publishUntilObserved(
                            firstService,
                            first,
                            firstObserved,
                            firstWriteCount
                        )
                    } finally {
                        writersDone.countDown()
                    }
                },
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        publishUntilObserved(
                            secondService,
                            second,
                            secondObserved,
                            secondWriteCount
                        )
                    } finally {
                        writersDone.countDown()
                    }
                },
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    observeWhileWriting(
                        firstService,
                        writersDone,
                        firstObserved,
                        secondObserved,
                        first,
                        second,
                        observed
                    )
                },
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    observeWhileWriting(
                        secondService,
                        writersDone,
                        firstObserved,
                        secondObserved,
                        first,
                        second,
                        observed
                    )
                }
            )

            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(
                executor.awaitTermination(10, TimeUnit.SECONDS)
            ).isTrue()
        }

        assertThat(observed).isNotEmpty
        assertThat(observed).allMatch { it == first || it == second }
        assertThat(observed).contains(first, second)
        assertThat(firstWriteCount.get()).isPositive()
        assertThat(secondWriteCount.get()).isPositive()
        assertThat(service.getMaintenanceConfig())
            .isIn(first, second)
    }

    private fun publishUntilObserved(
        targetService: MaintenanceModeService,
        config: MaintenanceModeService.MaintenanceConfig,
        observed: CountDownLatch,
        writeCount: AtomicInteger
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            targetService.setMaintenanceMode(
                config.enabled,
                config.startTime,
                config.endTime,
                config.noticeId
            )
            writeCount.incrementAndGet()
            if (observed.await(2, TimeUnit.MILLISECONDS)) {
                return
            }
        } while (System.nanoTime() < deadline)
        check(observed.count == 0L)
    }

    private fun observeWhileWriting(
        targetService: MaintenanceModeService,
        writersDone: CountDownLatch,
        firstObserved: CountDownLatch,
        secondObserved: CountDownLatch,
        first: MaintenanceModeService.MaintenanceConfig,
        second: MaintenanceModeService.MaintenanceConfig,
        observed: ConcurrentLinkedQueue<
            MaintenanceModeService.MaintenanceConfig
        >
    ) {
        do {
            val config = targetService.getMaintenanceConfig()
            observed += config
            when (config) {
                first -> firstObserved.countDown()
                second -> secondObserved.countDown()
            }
        } while (writersDone.count > 0L)
        repeat(20) {
            observed += targetService.getMaintenanceConfig()
        }
    }

    companion object {
        private const val CONFIG_KEY = "maintenance:config"
        private val LEGACY_MAINTENANCE_KEYS = listOf(
            "maintenance:enabled",
            "maintenance:startTime",
            "maintenance:endTime",
            "maintenance:noticeId"
        )
        private val MAINTENANCE_KEYS = setOf(
            CONFIG_KEY,
            *LEGACY_MAINTENANCE_KEYS.toTypedArray()
        )
    }
}
