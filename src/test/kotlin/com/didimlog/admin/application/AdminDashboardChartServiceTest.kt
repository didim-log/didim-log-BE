package com.didimlog.admin.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationResults

@DisplayName("AdminDashboardChartService 테스트")
class AdminDashboardChartServiceTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val service = AdminDashboardChartService(mongoTemplate)

    private val aggregationSlot = slot<Aggregation>()
    private val collectionSlot = slot<String>()

    private fun stubAggregate(vararg buckets: Pair<String, Number>) {
        every {
            mongoTemplate.aggregate(capture(aggregationSlot), capture(collectionSlot), eq(Document::class.java))
        } returns AggregationResults(
            buckets.map { (date, count) -> Document("_id", date).append("count", count) },
            Document()
        )
    }

    private fun pipeline(): List<Document> = aggregationSlot.captured.toPipeline(Aggregation.DEFAULT_CONTEXT)

    private fun dateToString(stage: Document): Document {
        val project = stage["\$project"] as Document
        return (project["date"] as Document)["\$dateToString"] as Document
    }

    @Test
    @DisplayName("버킷별 개수를 날짜 순서대로 누적 합산한다")
    fun `누적 합산`() {
        stubAggregate("2026-01-01" to 2, "2026-01-02" to 3L, "2026-01-03" to 0)

        val result = service.getChartData(ChartDataType.RETROSPECTIVE, ChartPeriod.DAILY)

        assertThat(result).containsExactly(
            ChartDataPoint("2026-01-01", 2L),
            ChartDataPoint("2026-01-02", 5L),
            ChartDataPoint("2026-01-03", 5L)
        )
        assertThat(collectionSlot.captured).isEqualTo("retrospectives")
    }

    @Test
    @DisplayName("집계 결과가 없으면 빈 목록을 반환한다")
    fun `빈 집계 결과`() {
        stubAggregate()

        assertThat(service.getChartData(ChartDataType.USER, ChartPeriod.MONTHLY)).isEmpty()
    }

    @Test
    @DisplayName("회원 차트는 가입일이 없는 레거시 회원을 현재 시각으로 대체해 집계한다")
    fun `회원 차트 레거시 가입일 대체`() {
        stubAggregate("2026-W01" to 1)

        service.getChartData(ChartDataType.USER, ChartPeriod.WEEKLY)

        assertThat(collectionSlot.captured).isEqualTo("students")
        val stages = pipeline()
        val dateToString = dateToString(stages[0])
        assertThat(dateToString["format"]).isEqualTo("%G-W%V")
        assertThat(dateToString["date"]).isEqualTo(Document("\$ifNull", listOf("\$createdAt", "\$\$NOW")))
        assertThat(dateToString).containsKey("timezone")
        assertThat(stages[1]).isEqualTo(
            Document("\$group", Document("_id", "\$date").append("count", Document("\$sum", 1)))
        )
        assertThat(stages[2]).isEqualTo(Document("\$sort", Document("_id", 1)))
    }

    @Test
    @DisplayName("해결 문제 차트는 성공한 풀이의 문제별 최초 해결 시각으로 집계한다")
    fun `해결 문제 차트 파이프라인`() {
        stubAggregate()

        service.getChartData(ChartDataType.SOLUTION, ChartPeriod.DAILY)

        assertThat(collectionSlot.captured).isEqualTo("students")
        val stages = pipeline()
        assertThat(stages[0]).isEqualTo(Document("\$unwind", "\$solutions.items"))
        assertThat(stages[1]).isEqualTo(Document("\$match", Document("solutions.items.result", "SUCCESS")))
        assertThat(stages[2]).isEqualTo(
            Document(
                "\$group",
                Document("_id", "\$solutions.items.problemId")
                    .append("firstSolvedAt", Document("\$min", "\$solutions.items.solvedAt"))
            )
        )
        val dateToString = dateToString(stages[3])
        assertThat(dateToString["date"]).isEqualTo("\$firstSolvedAt")
        assertThat(dateToString["format"]).isEqualTo("%Y-%m-%d")
    }

    @ParameterizedTest(name = "{0} 기간은 {1} 형식으로 그룹화한다")
    @CsvSource(
        "DAILY, %Y-%m-%d",
        "WEEKLY, %G-W%V",
        "MONTHLY, %Y-%m"
    )
    @DisplayName("기간별 날짜 포맷")
    fun `기간별 날짜 포맷`(period: ChartPeriod, expectedFormat: String) {
        stubAggregate()

        service.getChartData(ChartDataType.RETROSPECTIVE, period)

        val dateToString = dateToString(pipeline()[0])
        assertThat(dateToString["format"]).isEqualTo(expectedFormat)
        assertThat(dateToString["date"]).isEqualTo("\$createdAt")
    }
}
