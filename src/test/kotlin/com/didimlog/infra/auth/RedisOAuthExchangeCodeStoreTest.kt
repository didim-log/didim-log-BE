package com.didimlog.infra.auth

import com.didimlog.application.auth.oauth.OAuthExchangeCodeIdentity
import com.didimlog.domain.enums.Role
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
        val identity = identity()
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(
                HASHED_KEY,
                SERIALIZED_IDENTITY,
                Duration.ofSeconds(TTL_SECONDS)
            )
        } returns true

        val saved = store.save(CODE, identity, TTL_SECONDS)

        assertThat(saved).isTrue()
        verify(exactly = 1) {
            valueOperations.setIfAbsent(
                HASHED_KEY,
                SERIALIZED_IDENTITY,
                Duration.ofSeconds(TTL_SECONDS)
            )
        }
    }

    @Test
    @DisplayName("교환 코드 조회와 삭제를 SHA-256 키의 getAndDelete 한 번으로 처리한다")
    fun `consume uses hashed key and atomic getAndDelete`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every { valueOperations.getAndDelete(HASHED_KEY) } returns SERIALIZED_IDENTITY

        val result = store.consume(CODE)

        assertThat(result).isEqualTo(identity())
        verify(exactly = 1) {
            valueOperations.getAndDelete(HASHED_KEY)
        }
    }

    @Test
    @DisplayName("studentId만 저장한 이전 교환 코드는 소비한 뒤 거절한다")
    fun `legacy student id payload is rejected`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every { valueOperations.getAndDelete(HASHED_KEY) } returns STUDENT_ID

        assertThat(store.consume(CODE)).isNull()
        verify(exactly = 1) {
            valueOperations.getAndDelete(HASHED_KEY)
        }
    }

    @Test
    @DisplayName("이전 payload 버전의 교환 코드는 소비한 뒤 거절한다")
    fun `previous payload version is rejected`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.getAndDelete(HASHED_KEY)
        } returns "v1:c3R1ZGVudC0xMjM:7:USER"

        assertThat(store.consume(CODE)).isNull()
        verify(exactly = 1) {
            valueOperations.getAndDelete(HASHED_KEY)
        }
    }

    private fun identity(): OAuthExchangeCodeIdentity {
        return OAuthExchangeCodeIdentity(
            studentId = STUDENT_ID,
            bojId = BOJ_ID,
            credentialVersion = CREDENTIAL_VERSION,
            role = Role.USER
        )
    }

    companion object {
        private const val CODE = "oauth-exchange-test-code"
        private const val STUDENT_ID = "student-123"
        private const val BOJ_ID = "oauth_boj_123"
        private const val CREDENTIAL_VERSION = 7L
        private const val TTL_SECONDS = 90L
        private const val SERIALIZED_IDENTITY =
            "v2:c3R1ZGVudC0xMjM:b2F1dGhfYm9qXzEyMw:7:USER"
        private const val HASHED_KEY =
            "oauth:exchange:13b2c523eaf849009df6de69024cb40a9b90f4030818297496203b2ede853f7c"
    }
}
