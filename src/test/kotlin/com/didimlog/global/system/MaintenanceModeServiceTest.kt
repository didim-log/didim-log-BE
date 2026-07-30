package com.didimlog.global.system

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("MaintenanceModeService 테스트")
class MaintenanceModeServiceTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val store = mutableMapOf<String, String>()
    private val service = MaintenanceModeService(redisTemplate)

    @BeforeEach
    fun setUp() {
        store.clear()
        every { redisTemplate.opsForValue() } returns valueOps
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                ALL_MAINTENANCE_KEYS,
                any(),
                any()
            )
        } answers {
            store[CONFIG_KEY] = thirdArg<Array<out Any>>()[0].toString()
            LEGACY_MAINTENANCE_KEYS.forEach(store::remove)
            1L
        }
        every { valueOps.multiGet(any()) } answers {
            firstArg<Collection<String>>().map(store::get)
        }
        every {
            redisTemplate.delete(any<Collection<String>>())
        } answers {
            firstArg<Collection<String>>()
                .count { store.remove(it) != null }
                .toLong()
        }
    }

    @Test
    fun `종료 시간이 있으면 전체 설정을 TTL과 함께 한 번에 저장한다`() {
        val startTime = LocalDateTime.now().plusMinutes(5)
        val endTime = LocalDateTime.now().plusHours(1)
        LEGACY_MAINTENANCE_KEYS.forEach { store[it] = "legacy" }

        service.setMaintenanceMode(
            enabled = true,
            startTime = startTime,
            endTime = endTime,
            noticeId = "notice-1"
        )

        assertThat(store.keys).containsExactly(CONFIG_KEY)
        assertThat(service.getMaintenanceConfig()).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                startTime = startTime,
                endTime = endTime,
                noticeId = "notice-1"
            )
        )
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                ALL_MAINTENANCE_KEYS,
                any(),
                match<String> { it.toLongOrNull()?.let { ttl -> ttl > 0L } == true }
            )
        }
        verify(exactly = 1) { valueOps.multiGet(ALL_MAINTENANCE_KEYS) }
    }

    @Test
    fun `종료 시간이 없으면 만료 없이 전체 설정을 저장한다`() {
        val startTime = LocalDateTime.now().plusMinutes(5)

        service.setMaintenanceMode(
            enabled = true,
            startTime = startTime,
            noticeId = "notice-open-ended"
        )

        assertThat(store.keys).containsExactly(CONFIG_KEY)
        assertThat(service.getMaintenanceConfig()).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                startTime = startTime,
                noticeId = "notice-open-ended"
            )
        )
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                ALL_MAINTENANCE_KEYS,
                any(),
                ""
            )
        }
    }

    @Test
    fun `비활성화하면 현재 설정과 구 설정 키를 함께 삭제한다`() {
        ALL_MAINTENANCE_KEYS.forEach { store[it] = "stored" }

        service.setMaintenanceMode(false)

        assertThat(store).isEmpty()
        verify(exactly = 1) {
            redisTemplate.delete(ALL_MAINTENANCE_KEYS)
        }
    }

    @Test
    fun `종료 시간이 이미 지났으면 활성 설정을 저장하지 않는다`() {
        service.setMaintenanceMode(
            enabled = true,
            endTime = LocalDateTime.now().minusSeconds(1),
            noticeId = "expired"
        )

        assertThat(store).isEmpty()
        verify(exactly = 1) {
            redisTemplate.delete(ALL_MAINTENANCE_KEYS)
        }
        verify(exactly = 0) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `손상된 설정은 삭제하지 않고 비활성 상태로 처리한다`() {
        store[CONFIG_KEY] = "{not-json"

        val config = service.getMaintenanceConfig()

        assertThat(config)
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
        assertThat(store[CONFIG_KEY]).isEqualTo("{not-json")
        verify(exactly = 0) {
            redisTemplate.delete(any<Collection<String>>())
        }
    }

    @Test
    fun `만료된 설정은 새 설정을 지우지 않고 비활성 상태로 처리한다`() {
        val endTime = LocalDateTime.now().minusMinutes(1)
        store[CONFIG_KEY] =
            """{"enabled":true,"startTime":null,"endTime":"$endTime","noticeId":"notice-1"}"""

        val config = service.getMaintenanceConfig()

        assertThat(config)
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
        assertThat(store).containsKey(CONFIG_KEY)
        verify(exactly = 0) {
            redisTemplate.delete(any<Collection<String>>())
        }
    }

    @Test
    fun `비활성 설정의 시간과 공지 ID는 노출하지 않는다`() {
        store[CONFIG_KEY] =
            """{"enabled":false,"startTime":"2026-01-01T00:00:00","endTime":null,"noticeId":"notice-1"}"""

        val config = service.getMaintenanceConfig()

        assertThat(config)
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
    }

    @Test
    fun `알 수 없는 설정 필드가 있어도 기존 필드를 읽는다`() {
        store[CONFIG_KEY] =
            """{"enabled":true,"startTime":null,"endTime":null,"noticeId":"notice-1","schemaVersion":2}"""

        val config = service.getMaintenanceConfig()

        assertThat(config).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                noticeId = "notice-1"
            )
        )
    }

    @Test
    fun `새 설정이 없으면 활성화된 구 설정을 읽는다`() {
        val startTime = LocalDateTime.now().minusMinutes(1)
        val endTime = LocalDateTime.now().plusMinutes(10)
        store["maintenance:enabled"] = "true"
        store["maintenance:startTime"] = startTime.toString()
        store["maintenance:endTime"] = endTime.toString()
        store["maintenance:noticeId"] = "legacy-notice"

        val config = service.getMaintenanceConfig()

        assertThat(config).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                startTime = startTime,
                endTime = endTime,
                noticeId = "legacy-notice"
            )
        )
        verify(exactly = 1) {
            valueOps.multiGet(ALL_MAINTENANCE_KEYS)
        }
    }

    @Test
    fun `종료 시각이 없는 구 활성 설정은 무기한 설정으로 읽는다`() {
        store["maintenance:enabled"] = "true"
        store["maintenance:noticeId"] = "legacy-open-ended"

        val config = service.getMaintenanceConfig()

        assertThat(config).isEqualTo(
            MaintenanceModeService.MaintenanceConfig(
                enabled = true,
                noticeId = "legacy-open-ended"
            )
        )
    }

    @Test
    fun `종료 시각이 지난 구 설정은 삭제하지 않고 비활성 처리한다`() {
        store["maintenance:enabled"] = "true"
        store["maintenance:endTime"] =
            LocalDateTime.now().minusMinutes(1).toString()

        val config = service.getMaintenanceConfig()

        assertThat(config)
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
        assertThat(store).containsKey("maintenance:enabled")
        verify(exactly = 0) {
            redisTemplate.delete(any<Collection<String>>())
        }
    }

    @Test
    fun `손상된 새 설정이 있으면 구 활성 설정으로 되돌아가지 않는다`() {
        store[CONFIG_KEY] = "{not-json"
        store["maintenance:enabled"] = "true"
        store["maintenance:noticeId"] = "stale-legacy"

        val config = service.getMaintenanceConfig()

        assertThat(config)
            .isEqualTo(MaintenanceModeService.MaintenanceConfig(false))
    }

    companion object {
        private const val CONFIG_KEY = "maintenance:config"
        private val LEGACY_MAINTENANCE_KEYS = listOf(
            "maintenance:enabled",
            "maintenance:startTime",
            "maintenance:endTime",
            "maintenance:noticeId"
        )
        private val ALL_MAINTENANCE_KEYS = listOf(
            CONFIG_KEY,
            *LEGACY_MAINTENANCE_KEYS.toTypedArray()
        )
    }
}
