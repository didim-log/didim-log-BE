package com.didimlog.ai.application

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("AiUsageService 테스트")
class AiUsageServiceTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val service = AiUsageService(redisTemplate)

    /** 키 접두어로 저장값을 찾아 자정 경계에서도 날짜 계산에 의존하지 않도록 한다. */
    private val store = mutableMapOf<String, String>()
    private val requestedKeys = mutableListOf<List<String>>()

    private val scriptKeys = mutableListOf<List<String>>()
    private val scriptArgs = mutableListOf<List<Any>>()

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.multiGet(any()) } answers {
            val keys = firstArg<Collection<String>>().toList()
            requestedKeys += keys
            keys.map { key -> store.entries.firstOrNull { key.startsWith(it.key) }?.value }
        }
        every { valueOps.set(any(), any()) } just runs
    }

    private fun captureScriptCalls(vararg results: Long) {
        val queue = ArrayDeque(results.toList())
        every {
            redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg())
        } answers {
            scriptKeys += secondArg<List<String>>()
            scriptArgs += args.drop(2).flatMap { arg -> if (arg is Array<*>) arg.filterNotNull() else listOfNotNull(arg) }
            queue.removeFirst()
        }
    }

    @Test
    @DisplayName("설정이 없으면 기본값으로 사용 가능 상태를 반환한다")
    fun `기본 설정으로 사용 가능`() {
        val status = service.checkAvailability("student-1")

        assertThat(status).isEqualTo(
            AiUsageService.AiStatus(
                isEnabled = true,
                todayGlobalUsage = 0,
                globalLimit = 1000,
                userLimit = 5,
                requireBojForAiReview = true,
                todayUserUsage = 0
            )
        )
        val keys = requestedKeys.single()
        assertThat(keys.take(4)).containsExactly(
            "AI_SERVICE:ENABLED",
            "AI_CONFIG:LIMIT:GLOBAL",
            "AI_CONFIG:LIMIT:USER",
            "AI_CONFIG:REQUIRE_BOJ_FOR_AI_REVIEW"
        )
        assertThat(keys[4]).startsWith("AI_USAGE:GLOBAL:")
        assertThat(keys[5]).startsWith("AI_USAGE:USER:student-1:")
    }

    @Test
    @DisplayName("Redis가 null을 반환하거나 설정값이 숫자가 아니면 기본값을 사용한다")
    fun `비정상 설정값은 기본값`() {
        every { valueOps.multiGet(any()) } returns null
        assertThat(service.checkAvailability("student-1").globalLimit).isEqualTo(1000)

        every { valueOps.multiGet(any()) } answers {
            firstArg<Collection<String>>().map { key ->
                when {
                    key.startsWith("AI_CONFIG:LIMIT:") -> "not-a-number"
                    key.startsWith("AI_USAGE:") -> "broken"
                    else -> null
                }
            }
        }
        val status = service.checkAvailability("student-1")
        assertThat(status.globalLimit).isEqualTo(1000)
        assertThat(status.userLimit).isEqualTo(5)
        assertThat(status.todayGlobalUsage).isZero()
        assertThat(status.todayUserUsage).isZero()
    }

    @Test
    @DisplayName("서비스가 꺼져 있으면 사용할 수 없다")
    fun `서비스 비활성화`() {
        store["AI_SERVICE:ENABLED"] = "false"

        val exception = assertThrows<BusinessException> { service.checkAvailability("student-1") }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.AI_SERVICE_DISABLED)
    }

    @Test
    @DisplayName("전역 사용량이 한도에 도달하면 사용할 수 없다")
    fun `전역 한도 도달`() {
        store["AI_CONFIG:LIMIT:GLOBAL"] = "10"
        store["AI_USAGE:GLOBAL:"] = "10"

        val exception = assertThrows<BusinessException> { service.checkAvailability("student-1") }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.AI_GLOBAL_LIMIT_EXCEEDED)
    }

    @Test
    @DisplayName("사용자 사용량이 한도에 도달하면 한도를 안내하며 거절한다")
    fun `사용자 한도 도달`() {
        store["AI_CONFIG:LIMIT:USER"] = "3"
        store["AI_USAGE:USER:student-1:"] = "3"

        val exception = assertThrows<BusinessException> { service.checkAvailability("student-1") }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.AI_USER_LIMIT_EXCEEDED)
        assertThat(exception.message).contains("(3회)")
    }

    @Test
    @DisplayName("사용량 예약이 성공하면 오늘 날짜 키와 고유 예약 키를 반환한다")
    fun `사용량 예약 성공`() {
        captureScriptCalls(0L)

        val reservation = service.reserveUsage("student-1")

        val keys = scriptKeys.single()
        assertThat(keys.take(3)).containsExactly(
            "AI_SERVICE:ENABLED",
            "AI_CONFIG:LIMIT:GLOBAL",
            "AI_CONFIG:LIMIT:USER"
        )
        val date = keys[3].removePrefix("AI_USAGE:GLOBAL:")
        assertThat(date).matches("\\d{4}-\\d{2}-\\d{2}")
        assertThat(keys[4]).isEqualTo("AI_USAGE:USER:student-1:$date")
        assertThat(keys[5]).startsWith("AI_USAGE:RESERVATION:student-1:$date:")
        assertThat(reservation.reservationKey).isEqualTo(keys[5])
        assertThat(reservation.globalUsageKey).isEqualTo(keys[3])
        assertThat(reservation.userUsageKey).isEqualTo(keys[4])

        val args = scriptArgs.single()
        assertThat(args.take(2)).containsExactly("1000", "5")
        assertThat(args[2].toString().toLong()).isBetween(1L, 24 * 60 * 60 * 1000L)
    }

    @Test
    @DisplayName("예약 키가 충돌하면 새 예약 키로 다시 시도한다")
    fun `예약 키 충돌 재시도`() {
        captureScriptCalls(4L, 0L)

        val reservation = service.reserveUsage("student-1")

        assertThat(scriptKeys).hasSize(2)
        assertThat(scriptKeys[0][5]).isNotEqualTo(scriptKeys[1][5])
        assertThat(reservation.reservationKey).isEqualTo(scriptKeys[1][5])
    }

    @Test
    @DisplayName("예약 키 충돌이 반복되면 세 번 시도 후 실패한다")
    fun `예약 키 충돌 소진`() {
        captureScriptCalls(4L, 4L, 4L)

        assertThatThrownBy { service.reserveUsage("student-1") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("AI 사용량 예약 키를 생성할 수 없습니다.")
        assertThat(scriptKeys).hasSize(3)
    }

    @Test
    @DisplayName("예약 스크립트 결과에 따라 서비스 중지와 한도 초과를 구분한다")
    fun `예약 실패 결과 분류`() {
        store["AI_CONFIG:LIMIT:USER"] = "7"
        captureScriptCalls(1L, 2L, 3L, 99L)

        val disabled = assertThrows<BusinessException> { service.reserveUsage("student-1") }
        val global = assertThrows<BusinessException> { service.reserveUsage("student-1") }
        val user = assertThrows<BusinessException> { service.reserveUsage("student-1") }
        val unknown = assertThrows<IllegalStateException> { service.reserveUsage("student-1") }

        assertThat(disabled.errorCode).isEqualTo(ErrorCode.AI_SERVICE_DISABLED)
        assertThat(global.errorCode).isEqualTo(ErrorCode.AI_GLOBAL_LIMIT_EXCEEDED)
        assertThat(user.errorCode).isEqualTo(ErrorCode.AI_USER_LIMIT_EXCEEDED)
        assertThat(user.message).contains("(7회)")
        assertThat(unknown.message).contains("result=99")
    }

    @Test
    @DisplayName("예약 해제는 예약 키와 사용량 키로 스크립트를 실행하고 결과를 해석한다")
    fun `사용량 예약 해제`() {
        val reservation = AiUsageService.UsageReservation(
            reservationKey = "AI_USAGE:RESERVATION:student-1:2026-01-01:abc",
            globalUsageKey = "AI_USAGE:GLOBAL:2026-01-01",
            userUsageKey = "AI_USAGE:USER:student-1:2026-01-01"
        )
        captureScriptCalls(1L, 0L, -1L)

        assertThat(service.releaseUsage(reservation)).isTrue()
        assertThat(service.releaseUsage(reservation)).isFalse()
        assertThatThrownBy { service.releaseUsage(reservation) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("올바른 정수가 아닙니다")
        assertThat(scriptKeys.first()).containsExactly(
            "AI_USAGE:RESERVATION:student-1:2026-01-01:abc",
            "AI_USAGE:GLOBAL:2026-01-01",
            "AI_USAGE:USER:student-1:2026-01-01"
        )
    }

    @Test
    @DisplayName("관리 설정 변경은 대응하는 Redis 키에 문자열 값으로 저장된다")
    fun `관리 설정 변경`() {
        service.emergencyStop()
        service.setServiceEnabled(true)
        service.setRequireBojForAiReview(false)
        service.updateLimits(globalLimit = 500, userLimit = 10)

        verify { valueOps.set("AI_SERVICE:ENABLED", "false") }
        verify { valueOps.set("AI_SERVICE:ENABLED", "true") }
        verify { valueOps.set("AI_CONFIG:REQUIRE_BOJ_FOR_AI_REVIEW", "false") }
        verify { valueOps.set("AI_CONFIG:LIMIT:GLOBAL", "500") }
        verify { valueOps.set("AI_CONFIG:LIMIT:USER", "10") }
    }

    @Test
    @DisplayName("사용자 한도가 전역 한도를 넘으면 제한을 변경하지 않는다")
    fun `잘못된 한도 변경 거부`() {
        val exception = assertThrows<BusinessException> { service.updateLimits(globalLimit = 5, userLimit = 6) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { valueOps.set(any(), any()) }
    }

    @Test
    @DisplayName("전체 상태 조회는 사용자 사용량 없이 설정과 전역 사용량을 반환한다")
    fun `전체 상태 조회`() {
        store["AI_SERVICE:ENABLED"] = "FALSE"
        store["AI_CONFIG:LIMIT:GLOBAL"] = "300"
        store["AI_CONFIG:LIMIT:USER"] = "3"
        store["AI_CONFIG:REQUIRE_BOJ_FOR_AI_REVIEW"] = "false"
        store["AI_USAGE:GLOBAL:"] = "42"

        val status = service.getStatus()

        assertThat(status).isEqualTo(
            AiUsageService.AiStatus(
                isEnabled = false,
                todayGlobalUsage = 42,
                globalLimit = 300,
                userLimit = 3,
                requireBojForAiReview = false,
                todayUserUsage = null
            )
        )
        assertThat(requestedKeys.single()).hasSize(5)
    }

    @Test
    @DisplayName("BOJ 연동 필수 정책은 설정이 없으면 true로 본다")
    fun `BOJ 연동 필수 정책 조회`() {
        assertThat(service.isRequireBojForAiReview()).isTrue()

        store["AI_CONFIG:REQUIRE_BOJ_FOR_AI_REVIEW"] = "false"
        assertThat(service.isRequireBojForAiReview()).isFalse()
    }

    @Test
    @DisplayName("사용자 사용량 조회는 남은 횟수를 0 미만으로 내리지 않는다")
    fun `사용자 사용량 조회`() {
        store["AI_CONFIG:LIMIT:USER"] = "3"
        store["AI_USAGE:USER:student-1:"] = "5"

        val usage = service.getUserUsage("student-1")

        assertThat(usage).isEqualTo(
            AiUsageService.UserUsageInfo(limit = 3, usage = 5, remaining = 0, isServiceEnabled = true)
        )
    }
}
