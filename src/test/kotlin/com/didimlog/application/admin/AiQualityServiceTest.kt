package com.didimlog.application.admin

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationResults

@DisplayName("AiQualityService 테스트")
class AiQualityServiceTest {

    private val logRepository: LogRepository = mockk()
    private val mongoTemplate: MongoTemplate = mockk()
    private val service = AiQualityService(logRepository, mongoTemplate)

    private val reasonCountClass = Class.forName("com.didimlog.application.admin.AiQualityService\$ReasonCount")

    private fun reasonCount(reason: String, count: Int): Any {
        val constructor = reasonCountClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(reason, count)
    }

    private fun log(id: String?, review: String?, code: String) = Log(
        id = id,
        title = LogTitle("제목"),
        content = LogContent("내용"),
        code = LogCode(code),
        aiReview = review?.let(::AiReview),
        aiFeedbackStatus = AiFeedbackStatus.DISLIKE
    )

    @Test
    @DisplayName("피드백이 없으면 집계 없이 빈 통계를 반환한다")
    fun `피드백 없음`() {
        every { logRepository.countByAiFeedbackStatusNot(AiFeedbackStatus.NONE) } returns 0L

        val stats = service.getAiQualityStats()

        assertThat(stats).isEqualTo(
            AiQualityService.AiQualityStats(
                totalFeedbackCount = 0,
                positiveRate = 0.0,
                negativeReasons = emptyMap(),
                recentNegativeLogs = emptyList()
            )
        )
        verify(exactly = 0) { logRepository.countByAiFeedbackStatus(any()) }
    }

    @Test
    @DisplayName("긍정률, 부정 사유 집계, 최근 부정 평가 로그를 계산한다")
    fun `품질 통계 계산`() {
        val aggregationSlot = slot<Aggregation>()
        every { logRepository.countByAiFeedbackStatusNot(AiFeedbackStatus.NONE) } returns 8L
        every { logRepository.countByAiFeedbackStatus(AiFeedbackStatus.LIKE) } returns 6L
        every {
            mongoTemplate.aggregate(capture(aggregationSlot), eq("logs"), any<Class<Any>>())
        } returns AggregationResults(
            listOf(reasonCount("INACCURATE", 3), reasonCount("TOO_LONG", 1)),
            Document()
        )
        every {
            logRepository.findByAiFeedbackStatusOrderByCreatedAtDesc(AiFeedbackStatus.DISLIKE, PageRequest.of(0, 5))
        } returns listOf(
            log(id = "log-1", review = "리뷰", code = "x".repeat(250)),
            log(id = null, review = null, code = "short")
        )

        val stats = service.getAiQualityStats()

        assertThat(stats.totalFeedbackCount).isEqualTo(8L)
        assertThat(stats.positiveRate).isCloseTo(75.0, within(1e-9))
        assertThat(stats.negativeReasons).containsExactlyInAnyOrderEntriesOf(
            mapOf("INACCURATE" to 3, "TOO_LONG" to 1)
        )
        assertThat(stats.recentNegativeLogs).containsExactly(
            AiQualityService.RecentNegativeLog(id = "log-1", aiReview = "리뷰", codeSnippet = "x".repeat(200)),
            AiQualityService.RecentNegativeLog(id = "", aiReview = "", codeSnippet = "short")
        )

        val pipeline = aggregationSlot.captured.toPipeline(Aggregation.DEFAULT_CONTEXT)
        assertThat(pipeline[0]).isEqualTo(Document("\$match", Document("aiFeedbackStatus", AiFeedbackStatus.DISLIKE)))
        assertThat(pipeline[1]).isEqualTo(Document("\$match", Document("aiFeedbackReason", Document("\$ne", null))))
        assertThat(pipeline[2]).isEqualTo(
            Document("\$group", Document("_id", "\$aiFeedbackReason").append("count", Document("\$sum", 1)))
        )
    }
}
