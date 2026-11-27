package com.didimlog.domain.repository

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.domain.Retrospective
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.RankingPeriod
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime
import java.util.regex.Pattern
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationResults
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.UpdateDefinition

@DisplayName("RetrospectiveRepositoryImpl 테스트")
class RetrospectiveRepositoryImplTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val repository = RetrospectiveRepositoryImpl(mongoTemplate)

    private val querySlot = slot<Query>()
    private val updateSlot = slot<UpdateDefinition>()
    private val optionsSlot = slot<FindAndModifyOptions>()

    private fun retrospective(id: String? = null) = Retrospective(
        id = id,
        studentId = "student-1",
        problemId = "1000",
        content = "충분히 긴 회고 내용입니다.",
        summary = "요약",
        createdAt = LocalDateTime.of(2026, 1, 1, 12, 0),
        isBookmarked = true,
        mainCategory = ProblemCategory.DP,
        solutionResult = ProblemResult.SUCCESS,
        solvedCategory = "Greedy",
        solveTime = "15m"
    )

    private fun stubFindAndModify(result: Retrospective?) {
        every {
            mongoTemplate.findAndModify(
                capture(querySlot),
                capture(updateSlot),
                capture(optionsSlot),
                eq(Retrospective::class.java)
            )
        } returns result
    }

    @Test
    @DisplayName("학생과 문제 기준 upsert는 편집 필드를 갱신하고 생성 시각과 북마크는 최초 삽입 시에만 기록한다")
    fun `회고 upsert 쿼리`() {
        val saved = retrospective(id = "retro-1")
        stubFindAndModify(saved)

        val result = repository.upsertEditableFieldsByStudentAndProblem(retrospective())

        assertThat(result).isSameAs(saved)
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("studentId", "student-1").append("problemId", "1000")
        )
        val update = (updateSlot.captured as Update).updateObject
        assertThat(update["\$set"]).isEqualTo(
            Document("content", "충분히 긴 회고 내용입니다.")
                .append("summary", "요약")
                .append("mainCategory", "Dynamic Programming")
                .append("solutionResult", "SUCCESS")
                .append("solvedCategory", "Greedy")
                .append("solveTime", "15m")
        )
        assertThat(update["\$setOnInsert"]).isEqualTo(
            Document("createdAt", LocalDateTime.of(2026, 1, 1, 12, 0))
                .append("isBookmarked", true)
        )
        assertThat(optionsSlot.captured.isUpsert).isTrue()
        assertThat(optionsSlot.captured.isReturnNew).isTrue()
    }

    @Test
    @DisplayName("upsert 결과가 비어 있으면 예외가 발생한다")
    fun `회고 upsert 결과 없음`() {
        stubFindAndModify(null)

        assertThatThrownBy { repository.upsertEditableFieldsByStudentAndProblem(retrospective()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("소유 회고 수정은 ID와 학생 ID가 일치할 때만 적용되고 upsert하지 않는다")
    fun `소유 회고 수정`() {
        stubFindAndModify(null)

        val result = repository.updateEditableFieldsByIdAndStudent(
            retrospective(id = "retro-1").copy(mainCategory = null, solutionResult = null)
        )

        assertThat(result).isNull()
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "retro-1").append("studentId", "student-1")
        )
        val set = (updateSlot.captured as Update).updateObject["\$set"] as Document
        assertThat(set).containsEntry("mainCategory", null)
        assertThat(set).containsEntry("solutionResult", null)
        assertThat((updateSlot.captured as Update).updateObject).doesNotContainKey("\$setOnInsert")
        assertThat(optionsSlot.captured.isUpsert).isFalse()
    }

    @Test
    @DisplayName("ID가 없는 회고는 수정할 수 없다")
    fun `ID 없는 회고 수정 거부`() {
        assertThatThrownBy { repository.updateEditableFieldsByIdAndStudent(retrospective(id = null)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("북마크 토글은 현재 값을 원자적으로 반전하는 파이프라인 업데이트를 사용한다")
    fun `북마크 토글`() {
        val toggled = retrospective(id = "retro-1")
        stubFindAndModify(toggled)

        val result = repository.toggleBookmarkByIdAndStudentId("retro-1", "student-1")

        assertThat(result).isSameAs(toggled)
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "retro-1").append("studentId", "student-1")
        )
        val update = updateSlot.captured as AggregationUpdate
        assertThat(update.toPipeline(Aggregation.DEFAULT_CONTEXT)).containsExactly(
            Document("\$set", Document("isBookmarked", Document("\$not", listOf("\$isBookmarked"))))
        )
        assertThat(optionsSlot.captured.isUpsert).isFalse()
    }

    @Test
    @DisplayName("소유 회고 삭제는 ID와 학생 ID 조건으로 findAndRemove 한다")
    fun `소유 회고 삭제`() {
        val removed = retrospective(id = "retro-1")
        every {
            mongoTemplate.findAndRemove(capture(querySlot), eq(Retrospective::class.java))
        } returns removed

        val result = repository.findAndRemoveByIdAndStudentId("retro-1", "student-1")

        assertThat(result).isSameAs(removed)
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "retro-1").append("studentId", "student-1")
        )
    }

    @Test
    @DisplayName("학생 ID 목록이 비어 있으면 집계 없이 빈 결과를 반환한다")
    fun `학생별 회고 수 빈 입력`() {
        val result = repository.countByStudentIds(emptySet())

        assertThat(result).isEmpty()
        verify(exactly = 0) {
            mongoTemplate.aggregate(any<Aggregation>(), any<String>(), any<Class<*>>())
        }
    }

    @Test
    @DisplayName("학생별 회고 수는 학생 ID로 필터링한 그룹 집계 결과를 맵으로 변환한다")
    fun `학생별 회고 수 집계`() {
        val aggregationSlot = slot<Aggregation>()
        every {
            mongoTemplate.aggregate(
                capture(aggregationSlot),
                eq("retrospectives"),
                eq(StudentRetrospectiveCount::class.java)
            )
        } returns AggregationResults(
            listOf(StudentRetrospectiveCount("s1", 3L), StudentRetrospectiveCount("s2", 1L)),
            Document()
        )

        val result = repository.countByStudentIds(setOf("s1", "s2"))

        assertThat(result).containsExactlyInAnyOrderEntriesOf(mapOf("s1" to 3L, "s2" to 1L))
        val pipeline = aggregationSlot.captured.toPipeline(Aggregation.DEFAULT_CONTEXT)
        val match = pipeline.first()["\$match"] as Document
        assertThat((match["studentId"] as Document)["\$in"] as Collection<*>)
            .containsExactlyInAnyOrder("s1", "s2")
        assertThat(pipeline[1]).isEqualTo(
            Document("\$group", Document("_id", "\$studentId").append("retrospectiveCount", Document("\$sum", 1)))
        )
    }

    @Test
    @DisplayName("회고 수 랭킹은 facet 결과의 데이터와 전체 개수를 페이지로 변환한다")
    fun `회고 랭킹 facet 결과 변환`() {
        val aggregationSlot = slot<Aggregation>()
        every {
            mongoTemplate.aggregate(capture(aggregationSlot), eq("retrospectives"), eq(Document::class.java))
        } returns AggregationResults(
            listOf(
                Document(
                    "data",
                    listOf(
                        Document("studentId", "s1").append("retrospectiveCount", 5),
                        Document("studentId", "s2").append("retrospectiveCount", 2L),
                        "not-a-document"
                    )
                ).append("total", listOf(Document("total", 12)))
            ),
            Document()
        )

        val page = repository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, PageRequest.of(1, 2))

        assertThat(page.content).containsExactly(
            StudentRetrospectiveCount("s1", 5L),
            StudentRetrospectiveCount("s2", 2L)
        )
        assertThat(page.totalElements).isEqualTo(12L)

        val facet = aggregationSlot.captured.toPipeline(Aggregation.DEFAULT_CONTEXT).single()["\$facet"] as Document
        @Suppress("UNCHECKED_CAST")
        val dataPipeline = facet["data"] as List<Document>
        assertThat(dataPipeline.first()).isEqualTo(Document("\$match", Document()))
        assertThat(dataPipeline).contains(Document("\$skip", 2L), Document("\$limit", 2L))
        assertThat(dataPipeline.single { it.containsKey("\$sort") }["\$sort"]).isEqualTo(
            Document("retrospectiveCount", -1).append("student.rating", -1).append("studentId", 1)
        )
    }

    @Test
    @DisplayName("기간별 회고 랭킹은 기간 시작 이후 생성된 회고만 집계한다")
    fun `회고 랭킹 기간 필터`() {
        val aggregationSlot = slot<Aggregation>()
        every {
            mongoTemplate.aggregate(capture(aggregationSlot), eq("retrospectives"), eq(Document::class.java))
        } returns AggregationResults(listOf(Document()), Document())
        val before = LocalDateTime.now()

        val page = repository.findTopStudentsByRetrospectiveCount(RankingPeriod.WEEKLY, PageRequest.of(0, 10))

        assertThat(page.content).isEmpty()
        assertThat(page.totalElements).isZero()
        val facet = aggregationSlot.captured.toPipeline(Aggregation.DEFAULT_CONTEXT).single()["\$facet"] as Document
        @Suppress("UNCHECKED_CAST")
        val match = (facet["total"] as List<Document>).first()["\$match"] as Document
        val from = (match["createdAt"] as Document)["\$gte"] as LocalDateTime
        assertThat(from).isBetween(before.minusDays(7).minusSeconds(1), LocalDateTime.now().minusDays(7))
    }

    @Test
    @DisplayName("집계 결과가 없으면 빈 페이지를 반환한다")
    fun `회고 랭킹 결과 없음`() {
        every {
            mongoTemplate.aggregate(any<Aggregation>(), eq("retrospectives"), eq(Document::class.java))
        } returns AggregationResults(emptyList(), Document())

        val page = repository.findTopStudentsByRetrospectiveCount(RankingPeriod.DAILY, PageRequest.of(0, 10))

        assertThat(page.content).isEmpty()
        assertThat(page.totalElements).isZero()
    }

    @Test
    @DisplayName("검색 조건이 없으면 전체를 생성일 내림차순으로 조회한다")
    fun `회고 검색 조건 없음`() {
        val findSlot = slot<Query>()
        val found = retrospective(id = "retro-1")
        every { mongoTemplate.count(any<Query>(), eq(Retrospective::class.java)) } returns 1L
        every { mongoTemplate.find(capture(findSlot), eq(Retrospective::class.java)) } returns listOf(found)

        val page = repository.search(RetrospectiveSearchCondition(), PageRequest.of(0, 10))

        assertThat(page.content).containsExactly(found)
        assertThat(page.totalElements).isEqualTo(1L)
        assertThat(findSlot.captured.queryObject).isEmpty()
        assertThat(findSlot.captured.sortObject).isEqualTo(Document("createdAt", -1))
        assertThat(findSlot.captured.limit).isEqualTo(10)
    }

    @Test
    @DisplayName("검색 조건은 모두 AND로 결합되고 키워드는 정규식 메타문자를 이스케이프한다")
    fun `회고 검색 조건 결합`() {
        val findSlot = slot<Query>()
        every { mongoTemplate.count(any<Query>(), eq(Retrospective::class.java)) } returns 0L
        every { mongoTemplate.find(capture(findSlot), eq(Retrospective::class.java)) } returns emptyList()

        repository.search(
            RetrospectiveSearchCondition(
                keyword = " a+b ",
                category = ProblemCategory.GREEDY,
                solvedCategory = " DP(1) ",
                isBookmarked = true,
                studentId = "student-1"
            ),
            PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "problemId"))
        )

        @Suppress("UNCHECKED_CAST")
        val clauses = findSlot.captured.queryObject["\$and"] as List<Document>
        assertThat(clauses).hasSize(5)
        assertThat(clauses[0]).isEqualTo(Document("studentId", "student-1"))

        @Suppress("UNCHECKED_CAST")
        val keywordClauses = clauses[1]["\$or"] as List<Document>
        assertThat(keywordClauses.map { it.keys.single() }).containsExactly("content", "problemId")
        val keywordPattern = keywordClauses.first()["content"] as Pattern
        assertThat(keywordPattern.matcher("XA+BX").find()).isTrue()
        assertThat(keywordPattern.matcher("aab").find()).isFalse()

        assertThat(clauses[2]).isEqualTo(Document("mainCategory", ProblemCategory.GREEDY))
        val solvedPattern = clauses[3]["solvedCategory"] as Pattern
        assertThat(solvedPattern.matcher("Greedy, dp(1)").find()).isTrue()
        assertThat(clauses[4]).isEqualTo(Document("isBookmarked", true))
        assertThat(findSlot.captured.sortObject).isEqualTo(Document("problemId", 1))
    }

    @Test
    @DisplayName("공백 키워드와 북마크 false는 검색 조건에서 제외된다")
    fun `회고 검색 무시 조건`() {
        val findSlot = slot<Query>()
        every { mongoTemplate.count(any<Query>(), eq(Retrospective::class.java)) } returns 0L
        every { mongoTemplate.find(capture(findSlot), eq(Retrospective::class.java)) } returns emptyList()

        repository.search(
            RetrospectiveSearchCondition(keyword = "  ", solvedCategory = "", isBookmarked = false),
            PageRequest.of(0, 5)
        )

        assertThat(findSlot.captured.queryObject).isEmpty()
    }
}
