package com.didimlog.admin.application

import com.mongodb.client.AggregateIterable
import com.mongodb.client.MongoCollection
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate

@DisplayName("ProblemStatsService 테스트")
class ProblemStatsServiceTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val collection: MongoCollection<Document> = mockk()
    private val aggregateIterable: AggregateIterable<Document> = mockk()
    private val service = ProblemStatsService(mongoTemplate)

    @BeforeEach
    fun setUp() {
        every { mongoTemplate.getCollection("problems") } returns collection
    }

    @Test
    @DisplayName("문제가 없으면 집계 없이 빈 통계를 반환한다")
    fun `문제 없음`() {
        every { collection.countDocuments() } returns 0L

        val stats = service.getProblemStats()

        assertThat(stats).isEqualTo(ProblemStatsService.ProblemStats(0L, null, null, null, null))
        verify(exactly = 0) { collection.aggregate(any<List<Bson>>()) }
    }

    @Test
    @DisplayName("숫자 ID 문제만 대상으로 최소/최대 ID와 누락 필드의 최소 ID를 집계한다")
    fun `문제 통계 집계`() {
        val pipelineSlot = slot<List<Bson>>()
        every { collection.countDocuments() } returns 120L
        every { collection.aggregate(capture(pipelineSlot)) } returns aggregateIterable
        every { aggregateIterable.first() } returns Document("_id", null)
            .append("minProblemId", 1000)
            .append("maxProblemId", 3000)
            .append("minNullDescriptionHtmlProblemId", 1500)
            .append("minNullLanguageProblemId", null)

        val stats = service.getProblemStats()

        assertThat(stats).isEqualTo(ProblemStatsService.ProblemStats(120L, 1000, 3000, 1500, null))
        val pipeline = pipelineSlot.captured.map { it as Document }
        assertThat(pipeline[0]).isEqualTo(Document("\$match", Document("_id", Document("\$regex", "^[0-9]+$"))))
        val project = pipeline[1]["\$project"] as Document
        assertThat(project["numericProblemId"]).isEqualTo(Document("\$toInt", "\$_id"))
        val group = pipeline[2]["\$group"] as Document
        assertThat(group.keys).containsExactly(
            "_id",
            "minProblemId",
            "maxProblemId",
            "minNullDescriptionHtmlProblemId",
            "minNullLanguageProblemId"
        )
    }

    @Test
    @DisplayName("숫자 ID 문제가 없어 집계 결과가 비면 개수만 반환한다")
    fun `숫자 ID 문제 없음`() {
        every { collection.countDocuments() } returns 3L
        every { collection.aggregate(any<List<Bson>>()) } returns aggregateIterable
        every { aggregateIterable.first() } returns null

        val stats = service.getProblemStats()

        assertThat(stats).isEqualTo(ProblemStatsService.ProblemStats(3L, null, null, null, null))
    }
}
