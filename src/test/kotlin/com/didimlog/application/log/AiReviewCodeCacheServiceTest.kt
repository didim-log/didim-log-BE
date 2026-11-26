package com.didimlog.application.log

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@DisplayName("AiReviewCodeCacheService 테스트")
class AiReviewCodeCacheServiceTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val service = AiReviewCodeCacheService(redisTemplate)

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    @DisplayName("리뷰는 풀이 결과별 버킷과 정규화한 코드 해시로 7일간 캐시된다")
    fun `리뷰 캐시 저장 키`() {
        val key = slot<String>()
        every { valueOps.set(capture(key), "리뷰", Duration.ofDays(7)) } just runs

        service.cacheReview("  print(1)\n", true, "리뷰")

        assertThat(key.captured).isEqualTo("AI_REVIEW:CACHE:v1:success:${sha256("print(1)")}")
    }

    @Test
    @DisplayName("풀이 결과가 다르면 같은 코드라도 다른 캐시 키를 사용한다")
    fun `풀이 결과별 캐시 키 분리`() {
        val keys = mutableListOf<String>()
        every { valueOps.get(capture(keys)) } returns null

        service.getCachedReview("code", true)
        service.getCachedReview("code", false)
        service.getCachedReview("code", null)

        val hash = sha256("code")
        assertThat(keys).containsExactly(
            "AI_REVIEW:CACHE:v1:success:$hash",
            "AI_REVIEW:CACHE:v1:fail:$hash",
            "AI_REVIEW:CACHE:v1:unknown:$hash"
        )
    }

    @Test
    @DisplayName("2000자 이후만 다른 코드는 같은 캐시 키를 공유한다")
    fun `긴 코드 해시 범위`() {
        val prefix = "a".repeat(2_000)
        every { valueOps.get("AI_REVIEW:CACHE:v1:fail:${sha256(prefix)}") } returns "cached"

        assertThat(service.getCachedReview(prefix + "tail-1", false)).isEqualTo("cached")
        assertThat(service.getCachedReview(prefix + "tail-2", false)).isEqualTo("cached")
    }

    @Test
    @DisplayName("빈 리뷰는 캐시하지 않는다")
    fun `빈 리뷰 캐시 생략`() {
        service.cacheReview("code", true, "  ")

        verify(exactly = 0) { redisTemplate.opsForValue() }
    }
}
