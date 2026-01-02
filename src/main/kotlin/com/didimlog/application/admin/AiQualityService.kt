package com.didimlog.application.admin

import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.repository.LogRepository
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI 리뷰 품질 통계 서비스
 */
@Service
class AiQualityService(
    private val logRepository: LogRepository,
    private val mongoTemplate: MongoTemplate
) {

    /**
     * AI 리뷰 품질 통계를 조회합니다.
     *
     * @return AI 품질 통계 데이터
     */
    @Transactional(readOnly = true)
    fun getAiQualityStats(): AiQualityStats {
        // 전체 피드백 개수 (NONE 제외)
        val totalFeedbackCount = logRepository.countByAiFeedbackStatusNot(AiFeedbackStatus.NONE)
        
        if (totalFeedbackCount == 0L) {
            return AiQualityStats(
                totalFeedbackCount = 0,
                positiveRate = 0.0,
                negativeReasons = emptyMap(),
                recentNegativeLogs = emptyList()
            )
        }

        // LIKE 개수
        val likeCount = logRepository.countByAiFeedbackStatus(AiFeedbackStatus.LIKE)
        
        // 긍정률 계산
        val positiveRate = (likeCount.toDouble() / totalFeedbackCount.toDouble()) * 100.0

        // 부정적 피드백 이유별 통계
        val negativeReasons = getNegativeReasonStats()

        // 최근 부정 평가 로그 (상위 5개)
        val recentNegativeLogs = logRepository
            .findByAiFeedbackStatusOrderByCreatedAtDesc(AiFeedbackStatus.DISLIKE, org.springframework.data.domain.PageRequest.of(0, 5))
            .map { log ->
                RecentNegativeLog(
                    id = log.id ?: "",
                    aiReview = log.aiReview?.value ?: "",
                    codeSnippet = log.code.value.take(200) // 코드 일부만 (200자)
                )
            }

        return AiQualityStats(
            totalFeedbackCount = totalFeedbackCount,
            positiveRate = positiveRate,
            negativeReasons = negativeReasons,
            recentNegativeLogs = recentNegativeLogs
        )
    }

    /**
     * 부정적 피드백 이유별 통계를 조회합니다.
     */
    private fun getNegativeReasonStats(): Map<String, Int> {
        val aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("aiFeedbackStatus").`is`(AiFeedbackStatus.DISLIKE)),
            Aggregation.match(Criteria.where("aiFeedbackReason").ne(null)),
            Aggregation.group("aiFeedbackReason").count().`as`("count"),
            Aggregation.project("count").and("aiFeedbackReason").previousOperation()
        )

        val results = mongoTemplate.aggregate(
            aggregation,
            "logs",
            ReasonCount::class.java
        ).mappedResults

        return results.associate { it.aiFeedbackReason to it.count }
    }

    /**
     * MongoDB Aggregation 결과를 위한 데이터 클래스
     */
    private data class ReasonCount(
        val aiFeedbackReason: String,
        val count: Int
    )

    /**
     * AI 품질 통계 데이터
     */
    data class AiQualityStats(
        val totalFeedbackCount: Long,
        val positiveRate: Double,
        val negativeReasons: Map<String, Int>,
        val recentNegativeLogs: List<RecentNegativeLog>
    )

    /**
     * 최근 부정 평가 로그 정보
     */
    data class RecentNegativeLog(
        val id: String,
        val aiReview: String,
        val codeSnippet: String
    )
}

