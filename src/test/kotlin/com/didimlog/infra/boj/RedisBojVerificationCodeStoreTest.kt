package com.didimlog.infra.boj

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

@DisplayName("Redis BOJ 인증 코드 저장소 테스트")
class RedisBojVerificationCodeStoreTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOperations = mockk<ValueOperations<String, String>>()
    private val store = RedisBojVerificationCodeStore(redisTemplate)

    @Test
    @DisplayName("인증 세션 조회와 삭제를 getAndDelete 한 번으로 처리한다")
    fun `consume uses atomic getAndDelete`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every { valueOperations.getAndDelete("boj:verify:boj:verified:session") } returns "mekazon"

        val result = store.consume("boj:verified:session")

        assertThat(result).isEqualTo("mekazon")
        verify(exactly = 1) {
            valueOperations.getAndDelete("boj:verify:boj:verified:session")
        }
    }

    @Test
    @DisplayName("Rate Limit 횟수는 Redis INCR 결과를 반환하고 첫 요청에만 TTL을 설정한다")
    fun `incrementRateLimitCount returns incremented count`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every { valueOperations.increment("boj:rate:boj:code:rate:198.51.100.20") } returns 1L
        every {
            redisTemplate.expire(
                "boj:rate:boj:code:rate:198.51.100.20",
                Duration.ofSeconds(60L)
            )
        } returns true

        val count = store.incrementRateLimitCount("boj:code:rate:198.51.100.20", 60L)

        assertThat(count).isEqualTo(1L)
        verify(exactly = 1) {
            redisTemplate.expire(
                "boj:rate:boj:code:rate:198.51.100.20",
                Duration.ofSeconds(60L)
            )
        }
    }
}
