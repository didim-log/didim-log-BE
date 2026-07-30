package com.didimlog.application.recommendation

import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.didimlog.global.config.MongoConfig
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles

@DataMongoTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("추천 후보 Mongo 조회 통합 테스트")
@Import(
    RecommendationService::class,
    MongoConfig::class,
    RecommendationQueryMongoCommandConfiguration::class
)
class RecommendationQueryIntegrationTest {

    @Autowired
    private lateinit var recommendationService: RecommendationService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandRecorder: RecommendationQueryMongoCommandRecorder

    @BeforeEach
    fun setUp() {
        problemRepository.deleteAll()
        studentRepository.deleteAll()
        studentRepository.save(student())
        problemRepository.saveAll(
            listOf(
                problem("primary-min", ProblemCategory.BFS, level = 1),
                problem("primary-max", ProblemCategory.BFS, level = 5),
                problem("related-parent", ProblemCategory.GRAPH_THEORY),
                problem("related-tag", ProblemCategory.IMPLEMENTATION, tags = listOf(ProblemCategory.DFS.englishName)),
                problem("hierarchy-tag", ProblemCategory.IMPLEMENTATION, tags = listOf(ProblemCategory.BFS.englishName)),
                problem("overlap", ProblemCategory.BFS, tags = listOf(ProblemCategory.DFS.englishName)),
                problem("unrelated", ProblemCategory.GREEDY),
                problem("out-of-range", ProblemCategory.BFS, level = 6),
                problem(SUCCESS_PROBLEM_ID, ProblemCategory.BFS),
                problem(FAIL_PROBLEM_ID, ProblemCategory.BFS)
            )
        )
        mongoTemplate.getCollection(PROBLEM_COLLECTION).insertMany(
            listOf(
                legacyProblem("legacy-primary", ProblemCategory.BFS),
                legacyProblem(
                    "legacy-tag",
                    ProblemCategory.IMPLEMENTATION,
                    tags = listOf(ProblemCategory.DFS.englishName)
                ),
                legacyProblem(
                    "legacy-current-level-wins",
                    ProblemCategory.BFS,
                    level = 10
                )
            )
        )
        commandRecorder.enableAndReset()
    }

    @AfterEach
    fun tearDown() {
        commandRecorder.disableAndReset()
        problemRepository.deleteAll()
        studentRepository.deleteAll()
    }

    @Test
    fun `RELATED 추천은 한 번의 문제 조회로 경계값과 레거시 문서를 포함한다`() {
        val result = recommendationService.recommendProblemsDetailed(
            studentId = STUDENT_ID,
            count = 50,
            category = "BFS",
            filterMode = CategoryFilterMode.RELATED
        )
        val snapshot = commandRecorder.disableAndSnapshot()
        val resultById = result.associateBy { it.problem.id.value }

        assertThat(resultById.keys).containsExactlyInAnyOrder(
            "primary-min",
            "primary-max",
            "related-parent",
            "related-tag",
            "hierarchy-tag",
            "overlap",
            "legacy-primary",
            "legacy-tag"
        )
        assertThat(resultById.keys)
            .doesNotContain(
                "unrelated",
                "out-of-range",
                "legacy-current-level-wins",
                SUCCESS_PROBLEM_ID,
                FAIL_PROBLEM_ID
            )
        assertThat(resultById.getValue("primary-min").matchedByPrimary).isTrue()
        assertThat(resultById.getValue("primary-min").matchedByTags).isFalse()
        assertThat(resultById.getValue("related-tag").matchedByPrimary).isFalse()
        assertThat(resultById.getValue("related-tag").matchedByTags).isTrue()
        assertThat(resultById.getValue("overlap").matchedByPrimary).isTrue()
        assertThat(resultById.getValue("overlap").matchedByTags).isTrue()
        assertThat(resultById.getValue("legacy-primary").problem.level).isEqualTo(3)
        assertThat(resultById.getValue("legacy-tag").problem.level).isEqualTo(3)

        assertThat(snapshot.count("find", STUDENT_COLLECTION)).isEqualTo(1)
        assertThat(snapshot.count("find", PROBLEM_COLLECTION)).isEqualTo(1)
        assertThat(snapshot.count("aggregate", PROBLEM_COLLECTION)).isZero()
        assertThat(snapshot.count("getMore", STUDENT_COLLECTION)).isZero()
        assertThat(snapshot.count("getMore", PROBLEM_COLLECTION)).isZero()
    }

    @Test
    fun `EXACT와 HIERARCHY는 대표 카테고리와 태그 범위를 구분한다`() {
        val exactIds = recommendationService.recommendProblemsDetailed(
            studentId = STUDENT_ID,
            count = 50,
            category = "BFS",
            filterMode = CategoryFilterMode.EXACT
        ).map { it.problem.id.value }

        assertThat(exactIds).containsExactlyInAnyOrder(
            "primary-min",
            "primary-max",
            "overlap",
            "legacy-primary"
        )
        assertThat(exactIds).doesNotContain("hierarchy-tag", "related-parent", "related-tag", "legacy-tag")

        val hierarchyIds = recommendationService.recommendProblemsDetailed(
            studentId = STUDENT_ID,
            count = 50,
            category = "BFS",
            filterMode = CategoryFilterMode.HIERARCHY
        ).map { it.problem.id.value }

        assertThat(hierarchyIds).containsExactlyInAnyOrder(
            "primary-min",
            "primary-max",
            "hierarchy-tag",
            "overlap",
            "legacy-primary"
        )
        assertThat(hierarchyIds).doesNotContain("related-parent", "related-tag", "legacy-tag")
    }

    @Test
    fun `카테고리가 없어도 레거시 난이도를 읽고 현재 level을 우선한다`() {
        val resultIds = recommendationService.recommendProblemsDetailed(
            studentId = STUDENT_ID,
            count = 50
        ).map { it.problem.id.value }
        val snapshot = commandRecorder.disableAndSnapshot()

        assertThat(resultIds).contains("legacy-primary", "legacy-tag")
        assertThat(resultIds)
            .doesNotContain("legacy-current-level-wins", "out-of-range", SUCCESS_PROBLEM_ID, FAIL_PROBLEM_ID)
        assertThat(snapshot.count("find", STUDENT_COLLECTION)).isEqualTo(1)
        assertThat(snapshot.count("find", PROBLEM_COLLECTION)).isEqualTo(1)
        assertThat(snapshot.count("getMore", PROBLEM_COLLECTION)).isZero()
    }

    private fun student(): Student {
        val solutions = Solutions().apply {
            add(solution(SUCCESS_PROBLEM_ID, ProblemResult.SUCCESS))
            add(solution(FAIL_PROBLEM_ID, ProblemResult.FAIL))
        }
        return Student(
            id = STUDENT_ID,
            nickname = Nickname("query-test"),
            provider = Provider.BOJ,
            providerId = "recommendation-query-provider",
            solvedAcTierLevel = SolvedAcTierLevel(3),
            currentTier = Tier.BRONZE,
            role = Role.USER,
            solutions = solutions
        )
    }

    private fun solution(problemId: String, result: ProblemResult): Solution {
        return Solution(
            problemId = ProblemId(problemId),
            timeTaken = TimeTakenSeconds(100L),
            result = result
        )
    }

    private fun problem(
        id: String,
        category: ProblemCategory,
        tags: List<String> = emptyList(),
        level: Int = 3
    ): Problem {
        return Problem(
            id = ProblemId(id),
            title = id,
            category = category,
            difficulty = Tier.BRONZE,
            level = level,
            url = "https://example.test/$id",
            tags = tags
        )
    }

    private fun legacyProblem(
        id: String,
        category: ProblemCategory,
        tags: List<String> = emptyList(),
        level: Int? = null
    ): Document {
        return Document("_id", id)
            .append("title", id)
            .append("category", category.englishName)
            .append("difficulty", Tier.BRONZE.name)
            .append("difficultyLevel", 3)
            .append("url", "https://example.test/$id")
            .append("tags", tags)
            .append("language", "ko")
            .apply {
                level?.let { append("level", it) }
            }
    }

    private companion object {
        const val STUDENT_ID = "recommendation-query-student"
        const val SUCCESS_PROBLEM_ID = "solved-success"
        const val FAIL_PROBLEM_ID = "solved-fail"
        const val STUDENT_COLLECTION = "students"
        const val PROBLEM_COLLECTION = "problems"
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RecommendationQueryMongoCommandConfiguration {

    @Bean
    fun recommendationQueryMongoCommandRecorder(): RecommendationQueryMongoCommandRecorder {
        return RecommendationQueryMongoCommandRecorder()
    }

    @Bean
    fun recommendationQueryMongoCommandCustomizer(
        recorder: RecommendationQueryMongoCommandRecorder
    ): MongoClientSettingsBuilderCustomizer {
        return MongoClientSettingsBuilderCustomizer { builder: MongoClientSettings.Builder ->
            builder.addCommandListener(recorder)
        }
    }
}

class RecommendationQueryMongoCommandRecorder : CommandListener {
    private val commands = ConcurrentLinkedQueue<RecommendationQueryMongoCommand>()

    @Volatile
    private var enabled = false

    override fun commandStarted(event: CommandStartedEvent) {
        if (!enabled || event.commandName !in TRACKED_COMMANDS) {
            return
        }
        val collectionField = if (event.commandName == "getMore") {
            "collection"
        } else {
            event.commandName
        }
        val collection = (event.command[collectionField] as? BsonString)?.value ?: return
        if (collection !in TRACKED_COLLECTIONS) {
            return
        }
        commands.add(RecommendationQueryMongoCommand(event.commandName, collection))
    }

    fun enableAndReset() {
        commands.clear()
        enabled = true
    }

    fun disableAndReset() {
        enabled = false
        commands.clear()
    }

    fun disableAndSnapshot(): RecommendationQueryMongoCommandSnapshot {
        enabled = false
        val snapshot = commands.toList()
        commands.clear()
        return RecommendationQueryMongoCommandSnapshot(snapshot)
    }

    private companion object {
        val TRACKED_COMMANDS = setOf("find", "aggregate", "getMore")
        val TRACKED_COLLECTIONS = setOf("students", "problems")
    }
}

data class RecommendationQueryMongoCommand(
    val name: String,
    val collection: String
)

data class RecommendationQueryMongoCommandSnapshot(
    val commands: List<RecommendationQueryMongoCommand>
) {
    fun count(name: String, collection: String): Int {
        return commands.count { command ->
            command.name == name && command.collection == collection
        }
    }
}
