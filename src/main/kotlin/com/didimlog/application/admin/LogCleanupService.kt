package com.didimlog.application.admin

import com.didimlog.domain.enums.AiReviewStatus
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

    data class CleanupPlan(
        val mode: LogCleanupMode,
        val referenceDays: Int,
        val cutoffDate: LocalDateTime,
        val deletableCount: Long,
        val statusBreakdown: Map<String, Long>
    )

    data class CleanupResult(
        val mode: LogCleanupMode,
        val referenceDays: Int,
        val cutoffDate: LocalDateTime,
        val deletedCount: Long
    )

    /**
     * 수동으로 로그를 정리한다.
     *
     * @param mode 정리 모드
     * @param referenceDays 기준 일수
     * @return 삭제된 로그 수
     */
    @Transactional
    fun cleanupLogs(mode: LogCleanupMode, referenceDays: Int): CleanupResult {
        val plan = previewCleanup(mode, referenceDays)
        logRepository.deleteByCreatedAtBefore(plan.cutoffDate)
        log.info(
            "수동 로그 정리 완료: mode={}, referenceDays={}, cutoff={}, deleted={}",
            mode,
            referenceDays,
            plan.cutoffDate,
            plan.deletableCount
        )
        return CleanupResult(
            mode = mode,
            referenceDays = referenceDays,
            cutoffDate = plan.cutoffDate,
            deletedCount = plan.deletableCount
        )
    }

    /**
     * 수동 로그 정리 전 삭제 예정 건수를 미리 조회한다.
     */
    @Transactional(readOnly = true)
    fun previewCleanup(mode: LogCleanupMode, referenceDays: Int): CleanupPlan {
        val cutoffDate = resolveCutoffDate(mode, referenceDays)
        val deletableCount = logRepository.countByCreatedAtBefore(cutoffDate)
        val statusBreakdown = buildStatusBreakdown(cutoffDate)
        return CleanupPlan(
            mode = mode,
            referenceDays = referenceDays,
            cutoffDate = cutoffDate,
            deletableCount = deletableCount,
            statusBreakdown = statusBreakdown
        )
    }

    private fun resolveCutoffDate(mode: LogCleanupMode, referenceDays: Int): LocalDateTime {
        return when (mode) {
            LogCleanupMode.OLDER_THAN_DAYS -> LocalDateTime.now().minusDays(referenceDays.toLong())
            LogCleanupMode.KEEP_RECENT_DAYS -> LocalDateTime.now().minusDays(referenceDays.toLong())
        }
    }

    private fun buildStatusBreakdown(cutoffDate: LocalDateTime): Map<String, Long> {
        val completed = logRepository.countByCreatedAtBeforeAndAiReviewStatus(cutoffDate, AiReviewStatus.COMPLETED)
        val failed = logRepository.countByCreatedAtBeforeAndAiReviewStatus(cutoffDate, AiReviewStatus.FAILED)
        val inProgress = logRepository.countByCreatedAtBeforeAndAiReviewStatus(cutoffDate, AiReviewStatus.IN_PROGRESS)
        val unknown = logRepository.countByCreatedAtBeforeAndAiReviewStatusIsNull(cutoffDate)
        return linkedMapOf(
            "COMPLETED" to completed,
            "FAILED" to failed,
            "IN_PROGRESS" to inProgress,
            "UNKNOWN" to unknown
        )
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
