package com.didimlog.infra.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Gemini API Free Tier Rate Limiter
 * 
 * 제한사항:
 * - RPM (Requests Per Minute): 15회 (4초에 1회)
 * - RPD (Requests Per Day): 1,500회
 * 
 * Redis를 사용하여 분산 환경에서도 안전하게 Rate Limiting을 수행한다.
 */
@Component
class GeminiRateLimiter(
    private val redisTemplate: StringRedisTemplate
) {

    private val log = LoggerFactory.getLogger(GeminiRateLimiter::class.java)

    companion object {
        private const val RPM_KEY_PREFIX = "gemini:rate:rpm:"
        private const val RPD_KEY_PREFIX = "gemini:rate:rpd:"
        private const val LAST_REQUEST_KEY_PREFIX = "gemini:rate:last:"
        
        private const val MAX_RPM = 15L // 분당 최대 요청 수
        private const val MAX_RPD = 1500L // 일일 최대 요청 수
        private const val MIN_INTERVAL_SECONDS = 4L // 최소 요청 간격 (초)
    }

    /**
     * Rate Limit을 체크하고, 제한을 초과하면 예외를 발생시킨다.
     * 제한을 통과하면 카운터를 증가시킨다.
     *
     * @throws BusinessException RPM 또는 RPD 제한을 초과한 경우
     */
    fun checkAndIncrement() {
        val now = Instant.now()
        val currentMinute = now.epochSecond / 60
        val currentDay = now.epochSecond / 86400

        val rpmKey = "$RPM_KEY_PREFIX$currentMinute"
        val rpdKey = "$RPD_KEY_PREFIX$currentDay"
        val lastRequestKey = LAST_REQUEST_KEY_PREFIX

        // 최소 요청 간격 체크 (4초)
        checkMinimumInterval(lastRequestKey, now)

        // RPM 체크
        val currentRpm = checkRpm(rpmKey)

        // RPD 체크
        val currentRpd = checkRpd(rpdKey)

        // 모든 체크 통과 시 마지막 요청 시간 업데이트
        // (RPM, RPD 카운터는 checkRpm, checkRpd에서 이미 증가됨)
        updateLastRequestTime(lastRequestKey, now)

        log.debug(
            "Gemini API Rate Limit 체크 통과: RPM={}/{} (분당), RPD={}/{} (일일)",
            currentRpm,
            MAX_RPM,
            currentRpd,
            MAX_RPD
        )
    }

    /**
     * 최소 요청 간격(4초)을 체크한다.
     */
    private fun checkMinimumInterval(lastRequestKey: String, now: Instant) {
        val lastRequestTimeStr = redisTemplate.opsForValue().get(lastRequestKey)
        if (lastRequestTimeStr != null) {
            val lastRequestTime = Instant.ofEpochSecond(lastRequestTimeStr.toLong())
            val secondsSinceLastRequest = now.epochSecond - lastRequestTime.epochSecond

            if (secondsSinceLastRequest < MIN_INTERVAL_SECONDS) {
                val waitSeconds = MIN_INTERVAL_SECONDS - secondsSinceLastRequest
                log.warn(
                    "Gemini API Rate Limit: 최소 요청 간격 미달. {}초 대기 필요",
                    waitSeconds
                )
                throw BusinessException(
                    ErrorCode.AI_SERVICE_BUSY,
                    "무료 사용량이 많아 잠시 대기 중입니다. ${waitSeconds}초 후 다시 시도해주세요."
                )
            }
        }
    }

    /**
     * RPM (분당 요청 수)을 체크하고 증가시킨다.
     */
    private fun checkRpm(rpmKey: String): Long {
        val currentRpm = redisTemplate.opsForValue().increment(rpmKey) ?: 0L

        if (currentRpm == 1L) {
            // 첫 요청이면 TTL을 60초로 설정
            redisTemplate.expire(rpmKey, Duration.ofSeconds(60))
        }

        if (currentRpm > MAX_RPM) {
            log.warn("Gemini API Rate Limit 초과: RPM={}/{}", currentRpm, MAX_RPM)
            throw BusinessException(
                ErrorCode.AI_SERVICE_BUSY,
                "무료 사용량이 많아 잠시 대기 중입니다. 1분 후 다시 시도해주세요."
            )
        }

        return currentRpm - 1 // 증가 전 값을 반환 (현재 요청 제외)
    }

    /**
     * RPD (일일 요청 수)를 체크하고 증가시킨다.
     */
    private fun checkRpd(rpdKey: String): Long {
        val currentRpd = redisTemplate.opsForValue().increment(rpdKey) ?: 0L

        if (currentRpd == 1L) {
            // 첫 요청이면 TTL을 24시간으로 설정
            redisTemplate.expire(rpdKey, Duration.ofHours(24))
        }

        if (currentRpd > MAX_RPD) {
            log.warn("Gemini API Rate Limit 초과: RPD={}/{}", currentRpd, MAX_RPD)
            throw BusinessException(
                ErrorCode.AI_SERVICE_BUSY,
                "오늘의 무료 사용량을 모두 사용했습니다. 내일 다시 시도해주세요."
            )
        }

        return currentRpd - 1 // 증가 전 값을 반환 (현재 요청 제외)
    }

    /**
     * 마지막 요청 시간을 업데이트한다.
     */
    private fun updateLastRequestTime(lastRequestKey: String, now: Instant) {
        // 마지막 요청 시간 업데이트 (TTL 없음, 수동으로 관리)
        redisTemplate.opsForValue().set(lastRequestKey, now.epochSecond.toString())
    }

    /**
     * 현재 일일 사용량을 조회한다.
     *
     * @return 현재 일일 사용량 (0 이상)
     */
    fun getDailyUsage(): Long {
        val now = Instant.now()
        val currentDay = now.epochSecond / 86400
        val rpdKey = "$RPD_KEY_PREFIX$currentDay"
        val usage = redisTemplate.opsForValue().get(rpdKey)
        return usage?.toLongOrNull() ?: 0L
    }

    /**
     * 일일 사용량이 임계값에 근접했는지 확인한다.
     *
     * @param threshold 임계값 (기본값: 1400, MAX_RPD의 93%)
     * @return 임계값에 근접했으면 true
     */
    fun isNearDailyLimit(threshold: Long = 1400L): Boolean {
        return getDailyUsage() >= threshold
    }
}











