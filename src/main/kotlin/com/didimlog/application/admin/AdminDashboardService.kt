package com.didimlog.application.admin

import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import org.bson.Document
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

/**
 * 관리자 대시보드 서비스
 * 관리자용 통계 정보를 제공한다.
 */
@Service
class AdminDashboardService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository,
    private val mongoTemplate: MongoTemplate
) {

    /**
     * 관리자 대시보드 통계 정보를 조회한다.
     *
     * @return 대시보드 통계 정보
     */
    @Transactional(readOnly = true)
    fun getDashboardStats(): AdminDashboardStats {
        val totalUsers = studentRepository.count()
        val todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN)
        val todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX)

        val todaySignups = studentRepository.countByCreatedAtBetween(todayStart, todayEnd)

        // students.solutions 배열을 DB에서 직접 unwind+group 하여 고유 성공 problemId 수를 계산한다.
        val totalSolvedProblems = countDistinctSolvedProblems()

        val todayRetrospectives = retrospectiveRepository.countByCreatedAtBetween(todayStart, todayEnd)
        
        // AI 생성 통계 계산
        val aiMetrics = calculateAiMetrics()

        return AdminDashboardStats(
            totalUsers = totalUsers,
            todaySignups = todaySignups,
            totalSolvedProblems = totalSolvedProblems,
            todayRetrospectives = todayRetrospectives,
            aiMetrics = aiMetrics
        )
    }

    /**
     * AI 생성 시간 통계를 계산한다.
     *
     * @return AI 생성 통계 (평균 소요 시간, 총 생성 수, 타임아웃 수)
     */
    private fun calculateAiMetrics(): AiMetrics {
        val totalCount = mongoTemplate.count(
            Query(Criteria.where("aiReviewDurationMillis").ne(null)),
            "logs"
        )

        if (totalCount == 0L) {
            return AiMetrics(
                averageDurationMillis = null,
                totalGeneratedCount = 0L,
                timeoutCount = 0L
            )
        }

        val averageDuration = calculateAverageAiReviewDurationMillis()

        // 타임아웃: FAILED 이면서 duration null 또는 30초 이상
        val timeoutQuery = Query(
            Criteria().andOperator(
                Criteria.where("aiReviewStatus").`is`(com.didimlog.domain.enums.AiReviewStatus.FAILED),
                Criteria().orOperator(
                    Criteria.where("aiReviewDurationMillis").isNull(),
                    Criteria.where("aiReviewDurationMillis").gte(30_000)
                )
            )
        )
        val timeoutCount = mongoTemplate.count(timeoutQuery, "logs")

        return AiMetrics(
            averageDurationMillis = averageDuration,
            totalGeneratedCount = totalCount,
            timeoutCount = timeoutCount
        )
    }

    private fun countDistinctSolvedProblems(): Long {
        val aggregation = Aggregation.newAggregation(
            Aggregation.unwind("solutions.solutions"),
            Aggregation.match(Criteria.where("solutions.solutions.result").`is`("SUCCESS")),
            Aggregation.group("solutions.solutions.problemId.value"),
            Aggregation.count().`as`("count")
        )

        val result = mongoTemplate.aggregate(aggregation, "students", Document::class.java).uniqueMappedResult
        return (result?.get("count") as? Number)?.toLong() ?: 0L
    }

    private fun calculateAverageAiReviewDurationMillis(): Long? {
        val aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("aiReviewDurationMillis").ne(null)),
            Aggregation.group().avg("aiReviewDurationMillis").`as`("avgDuration")
        )
        val result = mongoTemplate.aggregate(aggregation, "logs", Document::class.java).uniqueMappedResult
        return (result?.get("avgDuration") as? Number)?.toLong()
    }
}

/**
 * 관리자 대시보드 통계 정보
 */
data class AdminDashboardStats(
    val totalUsers: Long,
    val todaySignups: Long,
    val totalSolvedProblems: Long,
    val todayRetrospectives: Long,
    val aiMetrics: AiMetrics
)

/**
 * AI 생성 통계 정보
 */
data class AiMetrics(
    val averageDurationMillis: Long?, // 평균 AI 생성 시간 (밀리초), null이면 아직 생성된 리뷰가 없음
    val totalGeneratedCount: Long, // 총 생성된 AI 리뷰 수
    val timeoutCount: Long // 타임아웃된 리뷰 수
)
