package com.didimlog.domain.repository

import com.didimlog.domain.Example
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.config.MongoConfig
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate

@DisplayName("Problem 메타데이터 부분 갱신 통합 테스트")
@DataMongoTest
@Import(MongoConfig::class)
class ProblemMetadataUpsertIntegrationTest {

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun setUp() {
        problemRepository.deleteAll()
    }

    @Test
    @DisplayName("문서가 없으면 기본 URL과 언어를 포함한 문제를 생성한다")
    fun insertsCompleteProblemWhenDocumentDoesNotExist() {
        val metadata = problemMetadata(
            id = "1000",
            title = "새 문제",
            category = ProblemCategory.DP,
            difficulty = Tier.GOLD,
            level = 13,
            tags = listOf("Dynamic Programming")
        )

        problemRepository.upsertMetadata(metadata)
        problemRepository.upsertMetadata(metadata.copy(title = "갱신된 문제"))

        val stored = problemRepository.findById("1000").orElseThrow()
        assertThat(stored.title).isEqualTo("갱신된 문제")
        assertThat(stored.category).isEqualTo(ProblemCategory.DP)
        assertThat(stored.difficulty).isEqualTo(Tier.GOLD)
        assertThat(stored.level).isEqualTo(13)
        assertThat(stored.tags).containsExactly("Dynamic Programming")
        assertThat(stored.url).isEqualTo("https://www.acmicpc.net/problem/1000")
        assertThat(stored.language).isEqualTo("ko")
        assertThat(problemRepository.count()).isEqualTo(1)

        val raw = rawProblem("1000")
        assertThat(raw.getString("category")).isEqualTo(ProblemCategory.DP.englishName)
        assertThat(raw.getString("difficulty")).isEqualTo(Tier.GOLD.name)
        assertThat(raw.getString("language")).isEqualTo("ko")
    }

    @Test
    @DisplayName("기존 문서는 메타데이터만 갱신하고 상세, URL과 언어를 보존한다")
    fun updatesOnlyMetadataAndRemovesLegacyLevel() {
        val existing = Problem(
            id = ProblemId("1001"),
            title = "이전 제목",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 1,
            url = "https://legacy.example/problems/1001",
            description = "기존 설명",
            inputDescription = "기존 입력 설명",
            outputDescription = "기존 출력 설명",
            examples = listOf(Example("기존 입력", "기존 출력")),
            descriptionHtml = "<p>기존 설명</p>",
            inputDescriptionHtml = "<p>기존 입력 설명</p>",
            outputDescriptionHtml = "<p>기존 출력 설명</p>",
            sampleInputs = listOf("1 2"),
            sampleOutputs = listOf("3"),
            tags = listOf(ProblemCategory.UNKNOWN.englishName),
            language = "en"
        )
        problemRepository.save(existing)
        mongoTemplate.getCollection(PROBLEM_COLLECTION).updateOne(
            Filters.eq("_id", existing.id.value),
            Updates.set(LEGACY_LEVEL_FIELD, 30)
        )

        problemRepository.upsertMetadata(
            problemMetadata(
                id = existing.id.value,
                title = "새 제목",
                category = ProblemCategory.ARITHMETIC,
                difficulty = Tier.SILVER,
                level = 7,
                tags = listOf("Arithmetic")
            )
        )

        val stored = problemRepository.findById(existing.id.value).orElseThrow()
        assertThat(stored.title).isEqualTo("새 제목")
        assertThat(stored.category).isEqualTo(ProblemCategory.ARITHMETIC)
        assertThat(stored.difficulty).isEqualTo(Tier.SILVER)
        assertThat(stored.level).isEqualTo(7)
        assertThat(stored.tags).containsExactly("Arithmetic")
        assertThat(stored.url).isEqualTo(existing.url)
        assertThat(stored.language).isEqualTo(existing.language)
        assertThat(stored.description).isEqualTo(existing.description)
        assertThat(stored.inputDescription).isEqualTo(existing.inputDescription)
        assertThat(stored.outputDescription).isEqualTo(existing.outputDescription)
        assertThat(stored.examples).isEqualTo(existing.examples)
        assertThat(stored.descriptionHtml).isEqualTo(existing.descriptionHtml)
        assertThat(stored.inputDescriptionHtml).isEqualTo(existing.inputDescriptionHtml)
        assertThat(stored.outputDescriptionHtml).isEqualTo(existing.outputDescriptionHtml)
        assertThat(stored.sampleInputs).isEqualTo(existing.sampleInputs)
        assertThat(stored.sampleOutputs).isEqualTo(existing.sampleOutputs)
        assertThat(problemRepository.count()).isEqualTo(1)

        val raw = rawProblem(existing.id.value)
        assertThat(raw.containsKey(LEGACY_LEVEL_FIELD)).isFalse()
    }

    private fun problemMetadata(
        id: String,
        title: String,
        category: ProblemCategory,
        difficulty: Tier,
        level: Int,
        tags: List<String>
    ): Problem {
        return Problem(
            id = ProblemId(id),
            title = title,
            category = category,
            difficulty = difficulty,
            level = level,
            url = "https://www.acmicpc.net/problem/$id",
            tags = tags
        )
    }

    private fun rawProblem(problemId: String) =
        mongoTemplate.getCollection(PROBLEM_COLLECTION)
            .find(Filters.eq("_id", problemId))
            .first()
            ?: error("문제 문서를 찾을 수 없습니다. problemId=$problemId")

    private companion object {
        const val PROBLEM_COLLECTION = "problems"
        const val LEGACY_LEVEL_FIELD = "difficultyLevel"
    }
}
