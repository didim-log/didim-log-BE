package com.didimlog.domain.repository

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.application.problem.collector.ProblemCollectorPacer
import com.didimlog.application.problem.collector.ProblemCollectorService
import com.didimlog.domain.Example
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.config.MongoConfig
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.crawler.ProblemDetails
import com.didimlog.infra.solvedac.SolvedAcClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate

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

    @Test
    @DisplayName("상세 부분 갱신은 먼저 읽은 객체가 오래돼도 최신 메타데이터와 언어를 보존한다")
    fun detailUpdatePreservesNewerMetadataAndLanguage() {
        val existing = problemMetadata(
            id = "1002",
            title = "이전 제목",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 1,
            tags = listOf("Unknown")
        ).copy(
            description = "레거시 설명",
            examples = listOf(Example("레거시 입력", "레거시 출력"))
        )
        problemRepository.save(existing)
        val stale = problemRepository.findById(existing.id.value).orElseThrow()

        problemRepository.upsertMetadata(
            problemMetadata(
                id = existing.id.value,
                title = "최신 제목",
                category = ProblemCategory.GRAPH_THEORY,
                difficulty = Tier.GOLD,
                level = 14,
                tags = listOf("Graphs")
            )
        )
        mongoTemplate.getCollection(PROBLEM_COLLECTION).updateOne(
            Filters.eq("_id", existing.id.value),
            Updates.set("language", "en")
        )

        val updated = problemRepository.updateDetails(
            stale.id.value,
            ProblemDetailsUpdate(
                descriptionHtml = "<p>새 상세</p>",
                inputDescriptionHtml = "<p>새 입력</p>",
                outputDescriptionHtml = "<p>새 출력</p>",
                sampleInputs = listOf("1"),
                sampleOutputs = listOf("2")
            )
        )

        assertThat(updated).isNotNull
        val stored = problemRepository.findById(existing.id.value).orElseThrow()
        assertThat(stored.title).isEqualTo("최신 제목")
        assertThat(stored.category).isEqualTo(ProblemCategory.GRAPH_THEORY)
        assertThat(stored.difficulty).isEqualTo(Tier.GOLD)
        assertThat(stored.level).isEqualTo(14)
        assertThat(stored.tags).containsExactly("Graphs")
        assertThat(stored.language).isEqualTo("en")
        assertThat(stored.description).isEqualTo("레거시 설명")
        assertThat(stored.examples).containsExactly(Example("레거시 입력", "레거시 출력"))
        assertThat(stored.descriptionHtml).isEqualTo("<p>새 상세</p>")
        assertThat(updated).isEqualTo(stored)
    }

    @Test
    @DisplayName("상세 수집 중 갱신된 메타데이터는 늦게 끝난 크롤링 결과에 덮어쓰이지 않는다")
    fun detailCollectionPreservesMetadataUpdatedAfterTargetRead() {
        val existing = problemMetadata(
            id = "1006",
            title = "수집 시작 전 제목",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 1,
            tags = listOf("Unknown")
        )
        problemRepository.save(existing)

        val details = ProblemDetails(
            descriptionHtml = "<p>수집한 상세</p>",
            inputDescriptionHtml = "<p>수집한 입력</p>",
            outputDescriptionHtml = "<p>수집한 출력</p>",
            sampleInputs = listOf("1"),
            sampleOutputs = listOf("2")
        )
        val crawler = object : BojCrawler() {
            override fun crawlProblemDetails(problemId: String): ProblemDetails {
                problemRepository.upsertMetadata(
                    problemMetadata(
                        id = problemId,
                        title = "수집 중 갱신된 제목",
                        category = ProblemCategory.GRAPH_THEORY,
                        difficulty = Tier.GOLD,
                        level = 14,
                        tags = listOf("Graphs")
                    )
                )
                mongoTemplate.getCollection(PROBLEM_COLLECTION).updateOne(
                    Filters.eq("_id", problemId),
                    Updates.set("language", "en")
                )
                return details
            }
        }
        val collector = ProblemCollectorService(
            solvedAcClient = mockk<SolvedAcClient>(),
            problemRepository = problemRepository,
            bojCrawler = crawler,
            redisTemplate = mockk<StringRedisTemplate>(),
            objectMapper = ObjectMapper(),
            adminAuditService = mockk<AdminAuditService>(),
            pacer = mockk<ProblemCollectorPacer>(relaxed = true),
            taskExecutor = null
        )

        collector.collectDetailsBatch()

        val stored = problemRepository.findById(existing.id.value).orElseThrow()
        assertThat(stored.title).isEqualTo("수집 중 갱신된 제목")
        assertThat(stored.category).isEqualTo(ProblemCategory.GRAPH_THEORY)
        assertThat(stored.difficulty).isEqualTo(Tier.GOLD)
        assertThat(stored.level).isEqualTo(14)
        assertThat(stored.tags).containsExactly("Graphs")
        assertThat(stored.language).isEqualTo("en")
        assertThat(stored.descriptionHtml).isEqualTo(details.descriptionHtml)
        assertThat(stored.sampleInputs).isEqualTo(details.sampleInputs)
        assertThat(problemRepository.count()).isEqualTo(1)
    }

    @Test
    @DisplayName("상세 새로고침은 상세와 언어만 함께 갱신한다")
    fun detailRefreshUpdatesOnlyDetailsAndLanguage() {
        val existing = problemMetadata(
            id = "1003",
            title = "메타데이터",
            category = ProblemCategory.DP,
            difficulty = Tier.SILVER,
            level = 8,
            tags = listOf("Dynamic Programming")
        ).copy(language = "ko")
        problemRepository.save(existing)

        val updated = problemRepository.updateDetails(
            existing.id.value,
            ProblemDetailsUpdate(
                descriptionHtml = "<p>English description</p>",
                inputDescriptionHtml = null,
                outputDescriptionHtml = null,
                sampleInputs = emptyList(),
                sampleOutputs = emptyList(),
                language = "en"
            )
        )

        assertThat(updated).isNotNull
        assertThat(updated!!.title).isEqualTo(existing.title)
        assertThat(updated.category).isEqualTo(existing.category)
        assertThat(updated.difficulty).isEqualTo(existing.difficulty)
        assertThat(updated.level).isEqualTo(existing.level)
        assertThat(updated.tags).isEqualTo(existing.tags)
        assertThat(updated.url).isEqualTo(existing.url)
        assertThat(updated.language).isEqualTo("en")
        assertThat(updated.descriptionHtml).isEqualTo("<p>English description</p>")
        assertThat(updated.inputDescriptionHtml).isNull()
        assertThat(updated.outputDescriptionHtml).isNull()
        assertThat(updated.sampleInputs).isEmpty()
        assertThat(updated.sampleOutputs).isEmpty()
    }

    @Test
    @DisplayName("언어 부분 갱신은 메타데이터와 상세를 보존한다")
    fun languageUpdatePreservesMetadataAndDetails() {
        val existing = problemMetadata(
            id = "1004",
            title = "언어 갱신 문제",
            category = ProblemCategory.STRING,
            difficulty = Tier.GOLD,
            level = 12,
            tags = listOf("String")
        ).copy(
            descriptionHtml = "<p>English description</p>",
            sampleInputs = listOf("input"),
            sampleOutputs = listOf("output"),
            language = "ko"
        )
        problemRepository.save(existing)

        val updated = problemRepository.updateLanguage(existing.id.value, "en")

        assertThat(updated).isTrue()
        assertThat(problemRepository.findById(existing.id.value).orElseThrow())
            .isEqualTo(existing.copy(language = "en"))
    }

    @Test
    @DisplayName("상세와 언어 부분 갱신은 삭제된 문제를 다시 만들지 않는다")
    fun partialUpdatesDoNotRecreateDeletedProblem() {
        val problemId = "1005"

        val detailsResult = problemRepository.updateDetails(
            problemId,
            ProblemDetailsUpdate(
                descriptionHtml = "<p>상세</p>",
                inputDescriptionHtml = null,
                outputDescriptionHtml = null,
                sampleInputs = null,
                sampleOutputs = null
            )
        )
        val languageResult = problemRepository.updateLanguage(problemId, "en")

        assertThat(detailsResult).isNull()
        assertThat(languageResult).isFalse()
        assertThat(problemRepository.existsById(problemId)).isFalse()
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
