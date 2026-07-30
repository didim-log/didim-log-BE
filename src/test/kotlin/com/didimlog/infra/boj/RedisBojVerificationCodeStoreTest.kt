package com.didimlog.infra.boj

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

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

}
