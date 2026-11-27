package com.didimlog.domain.repository

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.mongodb.client.result.UpdateResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.convert.MongoConverter
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("ProblemRepositoryImpl 테스트")
class ProblemRepositoryImplTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val converter: MongoConverter = mockk()
    private val repository = ProblemRepositoryImpl(mongoTemplate)

    private val querySlot = slot<Query>()
    private val updateSlot = slot<Update>()

    private fun problem(id: String = "1000") = Problem(
        id = ProblemId(id),
        title = "A+B",
        category = ProblemCategory.MATHEMATICS,
        difficulty = Tier.BRONZE,
        level = 3,
        url = "https://www.acmicpc.net/problem/$id",
        tags = listOf("math"),
        language = "ko"
    )

    @Test
    @DisplayName("메타데이터 upsert는 수집 필드만 갱신하고 URL과 언어는 최초 삽입 시에만 기록한다")
    fun `메타데이터 upsert`() {
        every {
            mongoTemplate.upsert(capture(querySlot), capture(updateSlot), eq(Problem::class.java))
        } returns UpdateResult.acknowledged(0L, 0L, null)

        repository.upsertMetadata(problem())

        assertThat(querySlot.captured.queryObject).isEqualTo(Document("_id", "1000"))
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("title", "A+B")
                    .append("category", "Mathematics")
                    .append("difficulty", "BRONZE")
                    .append("level", 3)
                    .append("tags", listOf("math"))
            )
                .append(
                    "\$setOnInsert",
                    Document("url", "https://www.acmicpc.net/problem/1000").append("language", "ko")
                )
                .append("\$unset", Document("difficultyLevel", 1))
        )
    }

    @Test
    @DisplayName("상세 갱신은 언어가 있을 때만 언어를 함께 갱신하고 문서를 새로 만들지 않는다")
    fun `상세 정보 갱신`() {
        val optionsSlot = slot<FindAndModifyOptions>()
        val updated = problem()
        every {
            mongoTemplate.findAndModify(
                capture(querySlot),
                capture(updateSlot),
                capture(optionsSlot),
                eq(Problem::class.java)
            )
        } returns updated

        val withLanguage = repository.updateDetails(
            "1000",
            ProblemDetailsUpdate("<p>d</p>", "<p>i</p>", "<p>o</p>", listOf("1"), listOf("2"), language = "en")
        )
        val withLanguageUpdate = updateSlot.captured.updateObject["\$set"] as Document

        repository.updateDetails("1000", ProblemDetailsUpdate(null, null, null, null, null))
        val withoutLanguageUpdate = updateSlot.captured.updateObject["\$set"] as Document

        assertThat(withLanguage).isSameAs(updated)
        assertThat(querySlot.captured.queryObject).isEqualTo(Document("_id", "1000"))
        assertThat(withLanguageUpdate).isEqualTo(
            Document("descriptionHtml", "<p>d</p>")
                .append("inputDescriptionHtml", "<p>i</p>")
                .append("outputDescriptionHtml", "<p>o</p>")
                .append("sampleInputs", listOf("1"))
                .append("sampleOutputs", listOf("2"))
                .append("language", "en")
        )
        assertThat(withoutLanguageUpdate).doesNotContainKey("language")
        assertThat(optionsSlot.captured.isUpsert).isFalse()
        assertThat(optionsSlot.captured.isReturnNew).isTrue()
    }

    @Test
    @DisplayName("언어 갱신은 대상 문제가 매칭될 때만 true를 반환한다")
    fun `언어 갱신`() {
        every {
            mongoTemplate.updateFirst(capture(querySlot), capture(updateSlot), eq(Problem::class.java))
        } returnsMany listOf(UpdateResult.acknowledged(1L, 0L, null), UpdateResult.acknowledged(0L, 0L, null))

        assertThat(repository.updateLanguage("1000", "en")).isTrue()
        assertThat(repository.updateLanguage("9999", "en")).isFalse()
        assertThat(querySlot.captured.queryObject).isEqualTo(Document("_id", "9999"))
        assertThat(updateSlot.captured.updateObject).isEqualTo(Document("\$set", Document("language", "en")))
    }

    @Test
    @DisplayName("레벨 범위 조회는 레거시 difficultyLevel 문서를 포함하고 level 값을 보정해 변환한다")
    fun `레벨 범위 조회 레거시 보정`() {
        val legacy = Document("_id", "1001").append("difficultyLevel", 5)
        val current = Document("_id", "1000").append("level", 4)
        val readDocuments = mutableListOf<Document>()
        every { mongoTemplate.getCollectionName(Problem::class.java) } returns "problems"
        every { mongoTemplate.find(capture(querySlot), eq(Document::class.java), eq("problems")) } returns
            listOf(current, legacy)
        every { mongoTemplate.converter } returns converter
        every { converter.read(Problem::class.java, any<Document>()) } answers {
            readDocuments += secondArg<Document>()
            problem(secondArg<Document>().getString("_id"))
        }

        val result = repository.findByLevelBetweenFlexible(3, 6)

        assertThat(result.map { it.id.value }).containsExactly("1000", "1001")
        assertThat(readDocuments[0]["level"]).isEqualTo(4)
        assertThat(readDocuments[1]["level"]).isEqualTo(5)
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document(
                "\$or",
                listOf(
                    Document("level", Document("\$gte", 3).append("\$lte", 6)),
                    Document(
                        "\$and",
                        listOf(
                            Document("level", null),
                            Document("difficultyLevel", Document("\$gte", 3).append("\$lte", 6))
                        )
                    )
                )
            )
        )
    }

    @Test
    @DisplayName("추천 후보 조회는 확장 태그가 있으면 카테고리 또는 태그 일치 조건을 사용한다")
    fun `추천 후보 확장 태그`() {
        every { mongoTemplate.getCollectionName(Problem::class.java) } returns "problems"
        every { mongoTemplate.find(capture(querySlot), eq(Document::class.java), eq("problems")) } returns emptyList()

        val result = repository.findRecommendationCandidates(1, 5, listOf("Greedy"), listOf("greedy", "sorting"))

        assertThat(result).isEmpty()
        @Suppress("UNCHECKED_CAST")
        val clauses = querySlot.captured.queryObject["\$and"] as List<Document>
        assertThat(clauses).hasSize(2)
        assertThat(clauses[0]).containsKey("\$or")
        assertThat(clauses[1]).isEqualTo(
            Document(
                "\$or",
                listOf(
                    Document("category", Document("\$in", listOf("Greedy"))),
                    Document("tags", Document("\$in", listOf("greedy", "sorting")))
                )
            )
        )
    }

    @Test
    @DisplayName("확장 태그가 없으면 카테고리 조건만 사용한다")
    fun `추천 후보 카테고리만`() {
        every { mongoTemplate.getCollectionName(Problem::class.java) } returns "problems"
        every { mongoTemplate.find(capture(querySlot), eq(Document::class.java), eq("problems")) } returns emptyList()

        repository.findRecommendationCandidates(1, 5, listOf("Greedy"), emptyList())

        @Suppress("UNCHECKED_CAST")
        val clauses = querySlot.captured.queryObject["\$and"] as List<Document>
        assertThat(clauses[1]).isEqualTo(Document("category", Document("\$in", listOf("Greedy"))))
    }
}
