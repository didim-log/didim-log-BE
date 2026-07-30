package com.didimlog.global.ratelimit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.dao.QueryTimeoutException

@DisplayName("Rate Limit 서비스 테스트")
class RateLimitServiceTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val service = RateLimitService(redisTemplate)

    @Test
    @DisplayName("허용 결과에는 남은 요청 수를 반환하고 재시도 시간은 비운다")
    fun `maps atomic script result to decision`() {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } returns listOf(1L, 3L, 60_001L)

        val decision = service.checkAndRecord("login:127.0.0.1", 5, 1)

        assertThat(decision).isEqualTo(
            RateLimitDecision(
                allowed = true,
                limit = 5,
                remainingRequests = 2,
                retryAfterSeconds = null
            )
        )
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        }
    }

    @Test
    @DisplayName("차단 결과는 남은 요청 수를 보정하고 재시도 시간을 초 단위로 올림한다")
    fun `blocked decision clamps remaining requests`() {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } returns listOf(0L, 6L, 60_001L)

        val decision = service.checkAndRecord("login:127.0.0.1", 5, 1)

        assertThat(decision.allowed).isFalse()
        assertThat(decision.remainingRequests).isZero()
        assertThat(decision.retryAfterSeconds).isEqualTo(61)
    }

    @Test
    @DisplayName("차단 구간의 TTL 경계값은 최소 1초로 반환한다")
    fun `blocked decision returns at least one retry second`() {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } returns listOf(0L, 5L, 0L)

        val decision = service.checkAndRecord("login:127.0.0.1", 5, 1)

        assertThat(decision.retryAfterSeconds).isEqualTo(1)
    }

    @Test
    @DisplayName("Redis 결과 형식이 다르면 요청을 우회시키지 않는다")
    fun `fails closed when script result is malformed`() {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } returns listOf(1L, 1L)

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 5, 1)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("Redis 결과가 숫자가 아니면 잘못된 요청으로 분류하지 않는다")
    fun `fails closed when script value is not numeric`() {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } returns listOf(1L, "not-a-number", 60_000L)

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 5, 1)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("Redis 결과의 만료 시간이 음수면 잘못된 서버 상태로 처리한다")
    fun `fails closed when script ttl is negative`() {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } returns listOf(0L, 5L, -1L)

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 5, 1)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("Redis 연결 실패는 인증 요청을 우회시키지 않고 503 예외로 바꾼다")
    fun `maps redis connection failure to service unavailable`() {
        val connectionFailure = RedisConnectionFailureException("Redis unavailable")
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } throws connectionFailure

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 5, 1)
        }
            .isInstanceOf(RateLimitUnavailableException::class.java)
            .hasCause(connectionFailure)
    }

    @Test
    @DisplayName("Redis 응답 시간 초과는 인증 요청을 우회시키지 않고 503 예외로 바꾼다")
    fun `maps redis timeout to service unavailable`() {
        val timeout = QueryTimeoutException("Redis timed out")
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                listOf(KEY),
                "5",
                "60000"
            )
        } throws timeout

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 5, 1)
        }
            .isInstanceOf(RateLimitUnavailableException::class.java)
            .hasCause(timeout)
    }

    @Test
    @DisplayName("최대 요청 수와 시간 구간은 양수여야 한다")
    fun `rejects non positive policy values`() {
        assertThatThrownBy {
            service.checkAndRecord(" ", 1, 1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 0, 1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            service.checkAndRecord("login:127.0.0.1", 1, 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        private const val KEY = "rate_limit:login:127.0.0.1"
    }
}
