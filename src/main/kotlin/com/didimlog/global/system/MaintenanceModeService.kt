package com.didimlog.global.system

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

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
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    companion object {
        private const val MAINTENANCE_CONFIG_KEY = "maintenance:config"
        private val LEGACY_MAINTENANCE_KEYS = listOf(
            "maintenance:enabled",
            "maintenance:startTime",
            "maintenance:endTime",
            "maintenance:noticeId"
        )
        private val ALL_MAINTENANCE_KEYS = listOf(
            MAINTENANCE_CONFIG_KEY,
            *LEGACY_MAINTENANCE_KEYS.toTypedArray()
        )
        private val WRITE_CONFIG_SCRIPT = DefaultRedisScript(
            """
            if ARGV[2] == '' then
                redis.call('SET', KEYS[1], ARGV[1])
            else
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            end

            for index = 2, #KEYS do
                redis.call('DEL', KEYS[index])
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )
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
        if (!enabled) {
            redisTemplate.delete(ALL_MAINTENANCE_KEYS)
            return
        }

        val config = MaintenanceConfig(
            enabled = true,
            startTime = startTime,
            endTime = endTime,
            noticeId = noticeId
        )
        val serializedConfig = objectMapper.writeValueAsString(config)
        val ttlMillis = endTime?.let {
            val ttl = Duration.between(LocalDateTime.now(), it)
            if (ttl.isNegative || ttl.isZero || ttl.toMillis() == 0L) {
                redisTemplate.delete(ALL_MAINTENANCE_KEYS)
                return
            }
            ttl.toMillis().toString()
        }.orEmpty()
        redisTemplate.execute(
            WRITE_CONFIG_SCRIPT,
            ALL_MAINTENANCE_KEYS,
            serializedConfig,
            ttlMillis
        )
    }

    private fun readConfig(serializedConfig: String): MaintenanceConfig {
        val config = try {
            objectMapper.readValue(
                serializedConfig,
                MaintenanceConfig::class.java
            )
        } catch (_: Exception) {
            return MaintenanceConfig(false)
        }
        return normalize(config)
    }

    /**
     * 유지보수 모드 활성화 여부를 확인한다.
     *
     * @return 활성화되어 있으면 true
     */
    fun isMaintenanceMode(): Boolean {
        return getMaintenanceConfig().enabled
    }

    /**
     * 유지보수 모드 설정 정보를 조회한다.
     *
     * @return 유지보수 모드 설정 정보
     */
    fun getMaintenanceConfig(): MaintenanceConfig {
        val storedValues = redisTemplate.opsForValue()
            .multiGet(ALL_MAINTENANCE_KEYS)
            ?: return MaintenanceConfig(false)
        val serializedConfig = storedValues.getOrNull(0)
        if (serializedConfig != null) {
            return readConfig(serializedConfig)
        }

        val legacyValues = storedValues.drop(1)
        if (legacyValues.getOrNull(0) != "true") {
            return MaintenanceConfig(false)
        }
        return normalize(
            MaintenanceConfig(
                enabled = true,
                startTime = legacyValues.getOrNull(1).toLocalDateTimeOrNull(),
                endTime = legacyValues.getOrNull(2).toLocalDateTimeOrNull(),
                noticeId = legacyValues.getOrNull(3)
            )
        )
    }

    private fun normalize(config: MaintenanceConfig): MaintenanceConfig {
        if (!config.enabled) {
            return MaintenanceConfig(false)
        }
        val endTime = config.endTime
        if (endTime != null && !LocalDateTime.now().isBefore(endTime)) {
            return MaintenanceConfig(false)
        }
        return config
    }

    private fun String?.toLocalDateTimeOrNull(): LocalDateTime? {
        return this?.let { runCatching(LocalDateTime::parse).getOrNull() }
    }
}
