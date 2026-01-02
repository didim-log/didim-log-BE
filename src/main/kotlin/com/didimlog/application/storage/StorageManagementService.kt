package com.didimlog.application.storage

import com.didimlog.domain.repository.RetrospectiveRepository
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 저장 공간 관리 서비스
 * 회고 데이터의 저장 공간 사용량을 모니터링하고 오래된 데이터를 정리합니다.
 */
@Service
class StorageManagementService(
    private val retrospectiveRepository: RetrospectiveRepository,
    private val mongoTemplate: MongoTemplate
) {

    /**
     * 저장 공간 통계를 조회합니다.
     *
     * @return 저장 공간 통계 (총 개수, 예상 크기, 가장 오래된 레코드 날짜)
     */
    @Transactional(readOnly = true)
    fun getStats(): StorageStats {
        val totalCount = retrospectiveRepository.count()
        val estimatedSizeKb = calculateEstimatedSize(totalCount)
        val oldestRecordDate = findOldestRecordDate()
        return StorageStats(
            totalCount = totalCount,
            estimatedSizeKb = estimatedSizeKb,
            oldestRecordDate = oldestRecordDate
        )
    }

    private fun calculateEstimatedSize(totalCount: Long): Long {
        return totalCount * 2L // 회고당 약 2KB로 추정
    }

    private fun findOldestRecordDate(): LocalDate {
        val oldestRecord = mongoTemplate.findOne(
            Query.query(Criteria.where("createdAt").exists(true))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "createdAt"))
                .limit(1),
            com.didimlog.domain.Retrospective::class.java
        )
        return oldestRecord?.createdAt?.toLocalDate() ?: LocalDate.now()
    }

    /**
     * 지정된 일수보다 오래된 회고를 삭제합니다.
     *
     * @param olderThanDays 기준일 (이보다 오래된 회고 삭제, 최소 30일)
     * @return 삭제된 회고 수
     */
    @Transactional
    fun deleteOldRetrospectives(olderThanDays: Int): Long {
        require(olderThanDays >= 30) { "최소 30일 이상의 데이터만 삭제할 수 있습니다. olderThanDays=$olderThanDays" }

        val cutoffDate = LocalDateTime.now().minusDays(olderThanDays.toLong())

        val query = Query.query(Criteria.where("createdAt").lt(cutoffDate))
        val deletedCount = mongoTemplate.remove(query, com.didimlog.domain.Retrospective::class.java).deletedCount

        return deletedCount
    }
}

/**
 * 저장 공간 통계 DTO
 */
data class StorageStats(
    val totalCount: Long,
    val estimatedSizeKb: Long,
    val oldestRecordDate: LocalDate
)

