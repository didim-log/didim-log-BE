package com.didimlog.application.retrospective

import com.didimlog.domain.repository.RetrospectiveRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 회고 데이터 정리 서비스
 * 보관 기간이 지난 회고를 자동으로 삭제한다.
 *
 * 참고: MongoDB TTL 인덱스는 Date 타입만 지원하므로, LocalDateTime을 사용하는 경우
 * 스케줄러 방식을 사용합니다. 만약 TTL 인덱스를 사용하려면 Retrospective 엔티티에
 * Date 타입의 필드를 추가하고 @Indexed(expireAfterSeconds = 15552000) 어노테이션을
 * 적용해야 합니다. (180일 = 180 * 24 * 60 * 60 = 15,552,000초)
 */
@Service
class RetrospectiveCleanupService(
    private val retrospectiveRepository: RetrospectiveRepository,
    @Value("\${app.retrospective.retention-days:180}")
    private val retentionDays: Int
) {
    private val log = LoggerFactory.getLogger(RetrospectiveCleanupService::class.java)

    /**
     * 수동으로 회고를 정리한다.
     *
     * @param olderThanDays 기준일 (이보다 오래된 회고 삭제)
     * @return 삭제된 회고 수
     */
    @Transactional
    fun cleanupRetrospectives(olderThanDays: Int): Long {
        val cutoffDate = LocalDateTime.now().minusDays(olderThanDays.toLong())
        val deletedCount = retrospectiveRepository.countByCreatedAtBefore(cutoffDate)
        retrospectiveRepository.deleteByCreatedAtBefore(cutoffDate)
        log.info("수동 회고 정리 완료: {}일 이상 된 회고 {}개 삭제", olderThanDays, deletedCount)
        return deletedCount
    }

    /**
     * 자동 회고 정리 스케줄러
     * 매일 새벽 3시에 실행되어 설정된 보관 기간(retention-days) 이상 된 회고를 자동으로 삭제한다.
     * 기본값은 180일(6개월)이며, application.yml에서 app.retrospective.retention-days로 설정 가능하다.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun autoCleanupRetrospectives() {
        val cutoffDate = LocalDateTime.now().minusDays(retentionDays.toLong())
        val deletedCount = retrospectiveRepository.countByCreatedAtBefore(cutoffDate)
        retrospectiveRepository.deleteByCreatedAtBefore(cutoffDate)
        log.info("자동 회고 정리 완료: {}일 이상 된 회고 {}개 삭제 (보관 기간: {}일)", retentionDays, deletedCount, retentionDays)
    }
}



