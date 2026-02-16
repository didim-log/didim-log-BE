package com.didimlog.application.log

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * 동일 코드에 대한 AI 리뷰 결과를 재사용하기 위한 캐시 서비스.
 */
@Service
class AiReviewCodeCacheService(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        private const val CACHE_PREFIX = "AI_REVIEW:CACHE"
        private const val CACHE_VERSION = "v1"
        private const val CACHE_TTL_DAYS = 7L
    }

    fun getCachedReview(code: String, isSuccess: Boolean?): String? {
        val key = buildKey(code, isSuccess)
        return redisTemplate.opsForValue().get(key)
    }

    fun cacheReview(code: String, isSuccess: Boolean?, review: String) {
        if (review.isBlank()) {
            return
        }
        val key = buildKey(code, isSuccess)
        redisTemplate.opsForValue().set(key, review, Duration.ofDays(CACHE_TTL_DAYS))
    }

    private fun buildKey(code: String, isSuccess: Boolean?): String {
        val resultBucket = when (isSuccess) {
            true -> "success"
            false -> "fail"
            null -> "unknown"
        }
        val normalizedCode = code.trim().take(2_000)
        val hash = sha256Hex(normalizedCode)
        return "$CACHE_PREFIX:$CACHE_VERSION:$resultBucket:$hash"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
