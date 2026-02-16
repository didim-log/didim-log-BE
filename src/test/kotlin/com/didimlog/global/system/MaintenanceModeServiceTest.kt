package com.didimlog.global.system

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

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
        every { valueOps.set(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            store[key] = value
            Unit
        }
        every { valueOps.get(any()) } answers { store[firstArg<String>()] }
        every { redisTemplate.delete(any<String>()) } answers {
            store.remove(firstArg<String>())
            true
        }
        every { redisTemplate.expire(any<String>(), any<Long>(), any<TimeUnit>()) } returns true
    }

    @Test
    fun `활성화 시 설정 정보를 저장한다`() {
        val start = LocalDateTime.now().plusMinutes(5)
        val end = LocalDateTime.now().plusHours(1)

        service.setMaintenanceMode(
            enabled = true,
            startTime = start,
            endTime = end,
            noticeId = "notice-1"
        )

        assertThat(store["maintenance:enabled"]).isEqualTo("true")
        assertThat(store["maintenance:startTime"]).isEqualTo(start.toString())
        assertThat(store["maintenance:endTime"]).isEqualTo(end.toString())
        assertThat(store["maintenance:noticeId"]).isEqualTo("notice-1")
        verify(exactly = 1) { redisTemplate.expire("maintenance:endTime", any(), TimeUnit.SECONDS) }
    }

    @Test
    fun `비활성화 시 설정 정보를 정리한다`() {
        store["maintenance:enabled"] = "true"
        store["maintenance:startTime"] = LocalDateTime.now().toString()
        store["maintenance:endTime"] = LocalDateTime.now().plusMinutes(10).toString()
        store["maintenance:noticeId"] = "notice-1"

        service.setMaintenanceMode(false)

        assertThat(store["maintenance:enabled"]).isEqualTo("false")
        assertThat(store).doesNotContainKeys(
            "maintenance:startTime",
            "maintenance:endTime",
            "maintenance:noticeId"
        )
    }

    @Test
    fun `종료 시간이 지난 경우 자동으로 비활성화된다`() {
        store["maintenance:enabled"] = "true"
        store["maintenance:endTime"] = LocalDateTime.now().minusMinutes(1).toString()
        store["maintenance:startTime"] = LocalDateTime.now().minusHours(1).toString()
        store["maintenance:noticeId"] = "notice-1"

        val enabled = service.isMaintenanceMode()

        assertThat(enabled).isFalse()
        assertThat(store["maintenance:enabled"]).isEqualTo("false")
        assertThat(store).doesNotContainKeys("maintenance:startTime", "maintenance:endTime", "maintenance:noticeId")
    }

    @Test
    fun `설정 조회 시 잘못된 날짜 포맷은 null로 처리한다`() {
        store["maintenance:enabled"] = "true"
        store["maintenance:startTime"] = "not-a-date"
        store["maintenance:endTime"] = "not-a-date"
        store["maintenance:noticeId"] = "notice-1"

        val config = service.getMaintenanceConfig()

        assertThat(config.enabled).isTrue()
        assertThat(config.startTime).isNull()
        assertThat(config.endTime).isNull()
        assertThat(config.noticeId).isEqualTo("notice-1")
    }
}

