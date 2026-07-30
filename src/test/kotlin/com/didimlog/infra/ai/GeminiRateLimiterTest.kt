package com.didimlog.infra.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.ratelimit.RateLimitUnavailableException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("Gemini 호출 제한 단위 테스트")
class GeminiRateLimiterTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val limiter = GeminiRateLimiter(
        redisTemplate,
        AiGeminiProperties(),
        Clock.fixed(NOW, ZoneOffset.UTC)
    )

    @Test
    @DisplayName("Lua 허용 결과는 예외 없이 통과한다")
    fun `maps allowed script result`() {
        stubScript(listOf(0L, 1L, 1L, 0L))

        limiter.checkAndIncrement()

        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                KEYS,
                *ARGS
            )
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [1L, 2L, 3L])
    @DisplayName("Lua 차단 결과는 AI 서비스 혼잡으로 반환한다")
    fun `maps denied script result`(decisionCode: Long) {
        stubScript(listOf(decisionCode, 15L, 100L, 2_500L))

        assertThatThrownBy {
            limiter.checkAndIncrement()
        }
            .isInstanceOf(BusinessException::class.java)
            .matches {
                (it as BusinessException).errorCode == ErrorCode.AI_SERVICE_BUSY
            }
    }

    @Test
    @DisplayName("손상 상태와 알 수 없는 결과 코드는 허용하지 않는다")
    fun `fails closed for invalid decision codes`() {
        stubScript(listOf(4L, 0L, 0L, 0L))
        assertThatThrownBy {
            limiter.checkAndIncrement()
        }.isInstanceOf(IllegalStateException::class.java)

        stubScript(listOf(99L, 0L, 0L, 0L))
        assertThatThrownBy {
            limiter.checkAndIncrement()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("잘못된 Lua 결과 형식은 허용하지 않는다")
    fun `fails closed for malformed script results`() {
        stubScript(listOf(0L, 1L))
        assertThatThrownBy {
            limiter.checkAndIncrement()
        }.isInstanceOf(IllegalStateException::class.java)

        stubScript(listOf(0L, "broken", 1L, 0L))
        assertThatThrownBy {
            limiter.checkAndIncrement()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("Redis 연결 실패는 재시도 가능한 503 예외로 바꾼다")
    fun `maps redis connection failure to unavailable`() {
        val failure = RedisConnectionFailureException("Redis unavailable")
        stubScriptFailure(failure)

        assertThatThrownBy {
            limiter.checkAndIncrement()
        }
            .isInstanceOf(RateLimitUnavailableException::class.java)
            .hasCause(failure)
    }

    @Test
    @DisplayName("Redis 응답 시간 초과는 재시도 가능한 503 예외로 바꾼다")
    fun `maps redis timeout to unavailable`() {
        val failure = QueryTimeoutException("Redis timed out")
        stubScriptFailure(failure)

        assertThatThrownBy {
            limiter.checkAndIncrement()
        }
            .isInstanceOf(RateLimitUnavailableException::class.java)
            .hasCause(failure)
    }

    @Test
    @DisplayName("호출 제한 설정은 유효한 범위만 허용한다")
    fun `rejects invalid rate limit properties`() {
        assertThatThrownBy {
            GeminiRateLimitProperties(minIntervalSeconds = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            GeminiRateLimitProperties(maxRpm = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            GeminiRateLimitProperties(maxRpd = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun stubScript(result: List<*>) {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                KEYS,
                *ARGS
            )
        } returns result
    }

    private fun stubScriptFailure(failure: RuntimeException) {
        every {
            redisTemplate.execute(
                any<RedisScript<List<*>>>(),
                KEYS,
                *ARGS
            )
        } throws failure
    }

    companion object {
        private val NOW = Instant.parse("2026-01-01T00:00:30Z")
        private const val RPM_KEY = "gemini:rate:rpm:29453760"
        private const val RPD_KEY = "gemini:rate:rpd:20454"
        private const val LAST_REQUEST_KEY = "gemini:rate:last:"
        private val KEYS = listOf(RPM_KEY, RPD_KEY, LAST_REQUEST_KEY)
        private val ARGS = arrayOf(
            "15",
            "1500",
            "30000",
            "86370000",
            "4000",
            "1767225630000",
            "1767225630"
        )
    }
}
