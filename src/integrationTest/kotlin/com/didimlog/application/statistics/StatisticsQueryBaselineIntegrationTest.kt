package com.didimlog.application.statistics

import com.didimlog.application.admin.query.MongoIndexBaseline
import com.didimlog.application.admin.query.MongoQueryExecutionBaseline
import com.didimlog.application.admin.query.MongoQueryPlanExplainer
import com.didimlog.application.admin.query.ObservedMongoReadCommand
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.config.mongo.MongoIndexInitializer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.bson.RawBsonDocument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles

@DisplayName("통계 조회 MongoDB 기준선")
@EnabledIfEnvironmentVariable(
    named = "STATISTICS_QUERY_BASELINE_ENABLED",
    matches = "true"
)
@DataMongoTest
@ActiveProfiles("test")
@Import(
    StatisticsService::class,
    StatisticsQueryBaselineMongoConfiguration::class
)
class StatisticsQueryBaselineIntegrationTest {

    @Autowired
    private lateinit var statisticsService: StatisticsService

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandRecorder: StatisticsMongoCommandRecorder

    private val objectMapper = jacksonObjectMapper()
    private val queryPlanExplainer by lazy { MongoQueryPlanExplainer(mongoTemplate) }

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        MongoIndexInitializer(mongoTemplate).ensureIndexes()
        seedFixture()
        commandRecorder.disableAndReset()
    }

    @AfterEach
    fun tearDown() {
        commandRecorder.disableAndReset()
        mongoTemplate.db.drop()
    }

    @Test
    fun `통계와 연도별 히트맵 조회 기준선을 기록한다`() {
        statisticsService.getStatistics(TARGET_STUDENT_ID)
        statisticsService.getHeatmapByYear(TARGET_STUDENT_ID, TARGET_YEAR)

        val mainResult = measureScenario("statistics-main") {
            statisticsService.getStatistics(TARGET_STUDENT_ID)
        }
        assertThat(mainResult.value.totalRetrospectives).isEqualTo(TARGET_RETROSPECTIVE_COUNT.toLong())
        writeSnapshot(
            "statistics-main.json",
            snapshot(
                scenario = "statistics-main",
                measurement = mainResult,
                functionalResult = canonicalStatistics(mainResult.value)
            )
        )

        val yearResult = measureScenario("statistics-year-$TARGET_YEAR") {
            statisticsService.getHeatmapByYear(TARGET_STUDENT_ID, TARGET_YEAR)
        }
        assertThat(yearResult.value).hasSize(TARGET_YEAR_RETROSPECTIVE_COUNT)
        writeSnapshot(
            "statistics-year-$TARGET_YEAR.json",
            snapshot(
                scenario = "statistics-year-$TARGET_YEAR",
                measurement = yearResult,
                functionalResult = canonicalHeatmap(yearResult.value)
            )
        )
    }

    private fun <T> measureScenario(
        scenario: String,
        action: () -> T
    ): ScenarioMeasurement<T> {
        commandRecorder.enableAndReset()
        val result = action()
        val capture = commandRecorder.disableAndSnapshot()
        val findCommand = capture.requireSingleFind()
        val replay = replayRawDocuments(findCommand)
        val queryPlan = queryPlanExplainer.explainFind(
            query = scenario,
            command = findCommand.toObservedCommand()
        )

        assertThat(replay.documentCount).isEqualTo(queryPlan.nReturned)
        return ScenarioMeasurement(
            value = result,
            capture = capture,
            replay = replay,
            queryPlan = queryPlan
        )
    }

    private fun replayRawDocuments(command: CapturedFindCommand): RawDocumentReplay {
        val rawCollection = mongoTemplate.db.getCollection(
            RETROSPECTIVE_COLLECTION,
            RawBsonDocument::class.java
        )
        val find = rawCollection.find(command.filter)
        command.projection?.let(find::projection)

        var documentCount = 0L
        var documentBsonBytes = 0L
        find.forEach { rawDocument ->
            documentCount += 1
            documentBsonBytes += rawDocument.byteBuffer.remaining().toLong()
        }
        return RawDocumentReplay(documentCount, documentBsonBytes)
    }

    private fun snapshot(
        scenario: String,
        measurement: ScenarioMeasurement<*>,
        functionalResult: Any
    ): StatisticsQuerySnapshot {
        val command = measurement.capture.findCommands.single()
        return StatisticsQuerySnapshot(
            source = BaselineSource(
                commitSha = System.getenv("STATISTICS_QUERY_BASELINE_COMMIT_SHA") ?: "NOT_CAPTURED",
                gitDirty = System.getenv("STATISTICS_QUERY_BASELINE_GIT_DIRTY")
                    ?.toBooleanStrictOrNull(),
                harnessSha256 = System.getenv("STATISTICS_QUERY_BASELINE_HARNESS_SHA256")
                    ?: "NOT_CAPTURED"
            ),
            database = mongoTemplate.db.name,
            fixture = StatisticsFixtureBaseline(
                targetStudentId = TARGET_STUDENT_ID,
                targetRetrospectiveCount = TARGET_RETROSPECTIVE_COUNT,
                distractorRetrospectiveCount = DISTRACTOR_RETROSPECTIVE_COUNT,
                contentCharacters = CONTENT_CHARACTER_COUNT,
                firstDate = FIXTURE_START_DATE.toString(),
                lastDate = FIXTURE_START_DATE
                    .plusDays((TARGET_RETROSPECTIVE_COUNT - 1).toLong())
                    .toString(),
                targetYear = TARGET_YEAR,
                targetYearRetrospectiveCount = TARGET_YEAR_RETROSPECTIVE_COUNT,
                zone = "Asia/Seoul"
            ),
            scenario = scenario,
            functionalResultSha256 = sha256(objectMapper.writeValueAsBytes(functionalResult)),
            command = StatisticsCommandBaseline(
                filter = command.filter.toJson(),
                projection = command.projection?.toJson(),
                retrospectiveFindCount = measurement.capture.findCount,
                retrospectiveGetMoreCount = measurement.capture.getMoreCount,
                retrospectiveReadCount = measurement.capture.findCount + measurement.capture.getMoreCount
            ),
            returnedDocumentCount = measurement.replay.documentCount,
            returnedDocumentBsonBytes = measurement.replay.documentBsonBytes,
            retrospectiveIndexes = queryPlanExplainer.collectIndexes(Retrospective::class.java),
            queryPlan = measurement.queryPlan
        )
    }

    private fun canonicalStatistics(statistics: StatisticsInfo): CanonicalStatistics {
        return CanonicalStatistics(
            monthlyHeatmap = canonicalHeatmap(statistics.monthlyHeatmap),
            totalSolvedCount = statistics.totalSolvedCount,
            totalRetrospectives = statistics.totalRetrospectives,
            totalFailures = statistics.totalFailures,
            averageSolveTime = statistics.averageSolveTime,
            successRate = statistics.successRate,
            categoryStats = statistics.categoryStats.sortedBy(CategoryStat::category),
            weaknessStats = statistics.weaknessStats.sortedBy(CategoryStat::category)
        )
    }

    private fun canonicalHeatmap(heatmap: List<HeatmapData>): List<HeatmapData> {
        return heatmap
            .map { item -> item.copy(problemIds = item.problemIds.sorted()) }
            .sortedBy(HeatmapData::date)
    }

    private fun writeSnapshot(fileName: String, snapshot: StatisticsQuerySnapshot) {
        val outputDirectory = System.getenv("STATISTICS_QUERY_BASELINE_OUTPUT_DIR")
            ?.takeIf(String::isNotBlank)
            ?: return
        val directory = Path.of(outputDirectory)
        Files.createDirectories(directory)
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(directory.resolve(fileName).toFile(), snapshot)
    }

    private fun seedFixture() {
        mongoTemplate.insert(student(TARGET_STUDENT_ID, "통계사용자"))
        mongoTemplate.insert(student(DISTRACTOR_STUDENT_ID, "다른사용자"))

        val content = "statistics-" + "x".repeat(CONTENT_CHARACTER_COUNT - "statistics-".length)
        val targetRetrospectives = (0 until TARGET_RETROSPECTIVE_COUNT).map { index ->
            retrospective(
                studentId = TARGET_STUDENT_ID,
                problemId = "target-$index",
                createdAt = FIXTURE_START_DATE.plusDays(index.toLong()).atTime(LocalTime.NOON),
                content = content,
                index = index
            )
        }
        val distractorRetrospectives = (0 until DISTRACTOR_RETROSPECTIVE_COUNT).map { index ->
            retrospective(
                studentId = DISTRACTOR_STUDENT_ID,
                problemId = "distractor-$index",
                createdAt = FIXTURE_START_DATE.plusDays(index.toLong()).atTime(LocalTime.NOON),
                content = content,
                index = index
            )
        }
        mongoTemplate.insert(
            targetRetrospectives + distractorRetrospectives,
            Retrospective::class.java
        )
    }

    private fun student(id: String, nickname: String): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = id,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }

    private fun retrospective(
        studentId: String,
        problemId: String,
        createdAt: LocalDateTime,
        content: String,
        index: Int
    ): Retrospective {
        val result = when (index % 4) {
            0 -> ProblemResult.SUCCESS
            1 -> ProblemResult.FAIL
            2 -> ProblemResult.TIME_OVER
            else -> null
        }
        val category = when (index % 3) {
            0 -> "DP, Graph"
            1 -> "Greedy"
            else -> null
        }
        return Retrospective(
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = "통계 조회 기준선 $problemId",
            createdAt = createdAt,
            solutionResult = result,
            solvedCategory = category
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val TARGET_STUDENT_ID = "statistics-target"
        private const val DISTRACTOR_STUDENT_ID = "statistics-distractor"
        private const val TARGET_RETROSPECTIVE_COUNT = 1_200
        private const val DISTRACTOR_RETROSPECTIVE_COUNT = 120
        private const val CONTENT_CHARACTER_COUNT = 4_096
        private const val TARGET_YEAR = 2024
        private const val TARGET_YEAR_RETROSPECTIVE_COUNT = 366
        private const val RETROSPECTIVE_COLLECTION = "retrospectives"
        private val FIXTURE_START_DATE = LocalDate.of(2022, 1, 1)
    }
}

@TestConfiguration
class StatisticsQueryBaselineMongoConfiguration {

    @Bean
    fun statisticsMongoCommandRecorder(): StatisticsMongoCommandRecorder {
        return StatisticsMongoCommandRecorder()
    }

    @Bean
    fun statisticsMongoCommandRecorderCustomizer(
        recorder: StatisticsMongoCommandRecorder
    ): MongoClientSettingsBuilderCustomizer {
        return MongoClientSettingsBuilderCustomizer { builder: MongoClientSettings.Builder ->
            builder.addCommandListener(recorder)
        }
    }
}

class StatisticsMongoCommandRecorder : CommandListener {

    private val readCommands = ConcurrentLinkedQueue<StatisticsReadCommand>()

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
        if (collection != RETROSPECTIVE_COLLECTION) {
            return
        }

        readCommands.add(
            StatisticsReadCommand(
                command = event.commandName,
                filter = (event.command["filter"] as? BsonDocument)?.clone(),
                projection = (event.command["projection"] as? BsonDocument)?.clone()
            )
        )
    }

    fun enableAndReset() {
        readCommands.clear()
        enabled = true
    }

    fun disableAndReset() {
        enabled = false
        readCommands.clear()
    }

    fun disableAndSnapshot(): StatisticsCommandCapture {
        enabled = false
        val snapshot = readCommands.toList()
        readCommands.clear()
        return StatisticsCommandCapture(
            findCommands = snapshot
                .filter { command -> command.command == "find" }
                .map { command ->
                    CapturedFindCommand(
                        filter = command.filter ?: BsonDocument(),
                        projection = command.projection
                    )
                },
            findCount = snapshot.count { command -> command.command == "find" },
            getMoreCount = snapshot.count { command -> command.command == "getMore" }
        )
    }

    companion object {
        private const val RETROSPECTIVE_COLLECTION = "retrospectives"
        private val TRACKED_COMMANDS = setOf("find", "getMore")
    }
}

data class StatisticsReadCommand(
    val command: String,
    val filter: BsonDocument?,
    val projection: BsonDocument?
)

data class StatisticsCommandCapture(
    val findCommands: List<CapturedFindCommand>,
    val findCount: Int,
    val getMoreCount: Int
) {
    fun requireSingleFind(): CapturedFindCommand {
        check(findCommands.size == 1) {
            "retrospectives find command가 정확히 1개가 아닙니다. commands=$findCommands"
        }
        return findCommands.single()
    }
}

data class CapturedFindCommand(
    val filter: BsonDocument,
    val projection: BsonDocument?
) {
    fun toObservedCommand(): ObservedMongoReadCommand {
        return ObservedMongoReadCommand(
            command = "find",
            collection = "retrospectives",
            filter = Document.parse(filter.toJson()),
            filterJson = filter.toJson(),
            projection = projection?.let { Document.parse(it.toJson()) },
            projectionJson = projection?.toJson(),
            sort = null,
            sortJson = null,
            skip = null,
            limit = null,
            batchSize = null,
            pipelineJson = null,
            pipeline = null
        )
    }
}

data class ScenarioMeasurement<T>(
    val value: T,
    val capture: StatisticsCommandCapture,
    val replay: RawDocumentReplay,
    val queryPlan: MongoQueryExecutionBaseline
)

data class RawDocumentReplay(
    val documentCount: Long,
    val documentBsonBytes: Long
)

data class StatisticsQuerySnapshot(
    val source: BaselineSource,
    val database: String,
    val fixture: StatisticsFixtureBaseline,
    val scenario: String,
    val functionalResultSha256: String,
    val command: StatisticsCommandBaseline,
    val returnedDocumentCount: Long,
    val returnedDocumentBsonBytes: Long,
    val retrospectiveIndexes: List<MongoIndexBaseline>,
    val queryPlan: MongoQueryExecutionBaseline
)

data class BaselineSource(
    val commitSha: String,
    val gitDirty: Boolean?,
    val harnessSha256: String
)

data class StatisticsFixtureBaseline(
    val targetStudentId: String,
    val targetRetrospectiveCount: Int,
    val distractorRetrospectiveCount: Int,
    val contentCharacters: Int,
    val firstDate: String,
    val lastDate: String,
    val targetYear: Int,
    val targetYearRetrospectiveCount: Int,
    val zone: String
)

data class StatisticsCommandBaseline(
    val filter: String,
    val projection: String?,
    val retrospectiveFindCount: Int,
    val retrospectiveGetMoreCount: Int,
    val retrospectiveReadCount: Int
)

data class CanonicalStatistics(
    val monthlyHeatmap: List<HeatmapData>,
    val totalSolvedCount: Int,
    val totalRetrospectives: Long,
    val totalFailures: Long,
    val averageSolveTime: Double,
    val successRate: Double,
    val categoryStats: List<CategoryStat>,
    val weaknessStats: List<CategoryStat>
)
