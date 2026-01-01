package com.didimlog.application.admin

import com.didimlog.domain.repository.LogRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 로그 정리 서비스
 * 오래된 로그를 자동/수동으로 삭제한다.
 */
@Service
class LogCleanupService(
    private val logRepository: LogRepository
) {
    private val log = LoggerFactory.getLogger(LogCleanupService::class.java)

    /**
     * 수동으로 로그를 정리한다.
     *
     * @param olderThanDays 기준일 (이보다 오래된 로그 삭제)
     * @return 삭제된 로그 수
     */
    @Transactional
    fun cleanupLogs(olderThanDays: Int): Long {
        val cutoffDate = LocalDateTime.now().minusDays(olderThanDays.toLong())
        val deletedCount = logRepository.countByCreatedAtBefore(cutoffDate)
        logRepository.deleteByCreatedAtBefore(cutoffDate)
        log.info("수동 로그 정리 완료: {}일 이상 된 로그 {}개 삭제", olderThanDays, deletedCount)
        return deletedCount
    }

    /**
     * 자동 로그 정리 스케줄러
     * 매일 새벽 3시에 실행되어 60일 이상 된 로그를 자동으로 삭제한다.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun autoCleanupLogs() {
        val retentionDays = 60
        val cutoffDate = LocalDateTime.now().minusDays(retentionDays.toLong())
        val deletedCount = logRepository.countByCreatedAtBefore(cutoffDate)
        logRepository.deleteByCreatedAtBefore(cutoffDate)
        log.info("자동 로그 정리 완료: {}일 이상 된 로그 {}개 삭제", retentionDays, deletedCount)
    }
}

