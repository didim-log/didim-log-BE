package com.didimlog.infra.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@DisplayName("Redis OAuth 교환 코드 저장소 테스트")
class RedisOAuthExchangeCodeStoreTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOperations = mockk<ValueOperations<String, String>>()
    private val store = RedisOAuthExchangeCodeStore(redisTemplate)

    @Test
    @DisplayName("교환 코드는 SHA-256 키에 TTL과 함께 덮어쓰기 없이 저장한다")
    fun `save uses hashed key ttl and setIfAbsent`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(
                HASHED_KEY,
                STUDENT_ID,
                Duration.ofSeconds(TTL_SECONDS)
            )
        } returns true

        val saved = store.save(CODE, STUDENT_ID, TTL_SECONDS)

        assertThat(saved).isTrue()
        verify(exactly = 1) {
            valueOperations.setIfAbsent(
                HASHED_KEY,
                STUDENT_ID,
                Duration.ofSeconds(TTL_SECONDS)
            )
        }
    }

    @Test
    @DisplayName("교환 코드 조회와 삭제를 SHA-256 키의 getAndDelete 한 번으로 처리한다")
    fun `consume uses hashed key and atomic getAndDelete`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every { valueOperations.getAndDelete(HASHED_KEY) } returns STUDENT_ID

        val result = store.consume(CODE)

        assertThat(result).isEqualTo(STUDENT_ID)
        verify(exactly = 1) {
            valueOperations.getAndDelete(HASHED_KEY)
        }
    }

    companion object {
        private const val CODE = "oauth-exchange-test-code"
        private const val STUDENT_ID = "student-123"
        private const val TTL_SECONDS = 90L
        private const val HASHED_KEY =
            "oauth:exchange:13b2c523eaf849009df6de69024cb40a9b90f4030818297496203b2ede853f7c"
    }
}
