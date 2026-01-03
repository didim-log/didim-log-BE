package com.didimlog.global.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 유지보수 모드 서비스
 * 서버를 끄지 않고 일반 사용자의 접근만 차단하는 기능을 제공한다.
 * Redis를 사용하여 점검 시간과 공지사항 ID를 저장한다.
 */
@Service
class MaintenanceModeService(
    private val redisTemplate: StringRedisTemplate
) {
    private val objectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerModule(KotlinModule.Builder().build())
    }

    companion object {
        private const val MAINTENANCE_ENABLED_KEY = "maintenance:enabled"
        private const val MAINTENANCE_START_TIME_KEY = "maintenance:startTime"
        private const val MAINTENANCE_END_TIME_KEY = "maintenance:endTime"
        private const val MAINTENANCE_NOTICE_ID_KEY = "maintenance:noticeId"
    }

    /**
     * 유지보수 모드 설정 데이터 클래스
     */
    data class MaintenanceConfig(
        val enabled: Boolean,
        val startTime: LocalDateTime? = null,
        val endTime: LocalDateTime? = null,
        val noticeId: String? = null
    )

    /**
     * 유지보수 모드를 설정한다.
     *
     * @param enabled 활성화 여부
     * @param startTime 점검 시작 시간 (선택)
     * @param endTime 점검 종료 시간 (선택)
     * @param noticeId 관련 공지사항 ID (선택)
     */
    fun setMaintenanceMode(
        enabled: Boolean,
        startTime: LocalDateTime? = null,
        endTime: LocalDateTime? = null,
        noticeId: String? = null
    ) {
        redisTemplate.opsForValue().set(MAINTENANCE_ENABLED_KEY, enabled.toString())
        
        if (!enabled) {
            // 점검 모드 비활성화 시 모든 정보 삭제
            redisTemplate.delete(MAINTENANCE_START_TIME_KEY)
            redisTemplate.delete(MAINTENANCE_END_TIME_KEY)
            redisTemplate.delete(MAINTENANCE_NOTICE_ID_KEY)
            return
        }
        
        // 점검 모드 활성화 시 시간 정보 저장
        setStartTimeIfPresent(startTime)
        setEndTimeIfPresent(endTime)
        setNoticeIdIfPresent(noticeId)
    }

    /**
     * 유지보수 모드 활성화 여부를 확인한다.
     *
     * @return 활성화되어 있으면 true
     */
    fun isMaintenanceMode(): Boolean {
        val enabledStr = redisTemplate.opsForValue().get(MAINTENANCE_ENABLED_KEY)
        if (enabledStr == null || enabledStr != "true") {
            return false
        }
        
        // 종료 시간이 지났으면 자동으로 비활성화
        val endTimeStr = redisTemplate.opsForValue().get(MAINTENANCE_END_TIME_KEY)
        if (endTimeStr != null) {
            try {
                val endTime = LocalDateTime.parse(endTimeStr)
                if (LocalDateTime.now().isAfter(endTime)) {
                    // 종료 시간이 지났으므로 비활성화
                    setMaintenanceMode(false)
                    return false
                }
            } catch (e: Exception) {
                // 파싱 실패 시 기존 동작 유지
            }
        }
        
        return true
    }

    /**
     * 유지보수 모드 설정 정보를 조회한다.
     *
     * @return 유지보수 모드 설정 정보
     */
    fun getMaintenanceConfig(): MaintenanceConfig {
        val enabled = isMaintenanceMode()
        val startTimeStr = redisTemplate.opsForValue().get(MAINTENANCE_START_TIME_KEY)
        val endTimeStr = redisTemplate.opsForValue().get(MAINTENANCE_END_TIME_KEY)
        val noticeId = redisTemplate.opsForValue().get(MAINTENANCE_NOTICE_ID_KEY)
        
        val startTime = startTimeStr?.let { 
            try {
                LocalDateTime.parse(it)
            } catch (e: Exception) {
                null
            }
        }
        
        val endTime = endTimeStr?.let {
            try {
                LocalDateTime.parse(it)
            } catch (e: Exception) {
                null
            }
        }
        
        return MaintenanceConfig(
            enabled = enabled,
            startTime = startTime,
            endTime = endTime,
            noticeId = noticeId
        )
    }
    
    private fun setStartTimeIfPresent(startTime: LocalDateTime?) {
        if (startTime == null) {
            redisTemplate.delete(MAINTENANCE_START_TIME_KEY)
            return
        }
        redisTemplate.opsForValue().set(MAINTENANCE_START_TIME_KEY, startTime.toString())
    }
    
    private fun setEndTimeIfPresent(endTime: LocalDateTime?) {
        if (endTime == null) {
            redisTemplate.delete(MAINTENANCE_END_TIME_KEY)
            return
        }
        redisTemplate.opsForValue().set(MAINTENANCE_END_TIME_KEY, endTime.toString())
        // 종료 시간이 지나면 자동으로 비활성화되도록 TTL 설정 (최대 7일)
        val ttlSeconds = java.time.Duration.between(LocalDateTime.now(), endTime).seconds
        if (ttlSeconds > 0) {
            redisTemplate.expire(MAINTENANCE_END_TIME_KEY, ttlSeconds, TimeUnit.SECONDS)
        }
    }
    
    private fun setNoticeIdIfPresent(noticeId: String?) {
        if (noticeId == null) {
            redisTemplate.delete(MAINTENANCE_NOTICE_ID_KEY)
            return
        }
        redisTemplate.opsForValue().set(MAINTENANCE_NOTICE_ID_KEY, noticeId)
    }
}



