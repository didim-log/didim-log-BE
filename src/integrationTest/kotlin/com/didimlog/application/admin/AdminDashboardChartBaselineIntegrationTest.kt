package com.didimlog.application.admin

import com.didimlog.domain.Retrospective
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.bson.RawBsonDocument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
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
@DisplayName("관리자 대시보드 차트 실제 MongoDB 기준선")
@EnabledIfEnvironmentVariable(
    named = "ADMIN_DASHBOARD_CHART_BASELINE_ENABLED",
    matches = "true"
)
@Import(
    AdminDashboardChartService::class,
    AdminDashboardChartBaselineMongoConfiguration::class
)
class AdminDashboardChartBaselineIntegrationTest {

    @Autowired
    private lateinit var chartService: AdminDashboardChartService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandRecorder: AdminDashboardChartBaselineCommandRecorder

    private val objectMapper = jacksonObjectMapper()
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        seedFixture()
        commandRecorder.disableAndReset()
    }

    @AfterEach
    fun tearDown() {
        commandRecorder.disableAndReset()
        mongoTemplate.db.drop()
    }

    @Test
    fun `전체 문서 조회와 서버 집계의 반환량 및 기능 결과를 기록한다`() {
        val measurements = ChartDataType.entries.map(::measure)

        measurements.forEach { measurement ->
            if (measurement.dataType == ChartDataType.SOLUTION.name) {
                assertThat(measurement.currentFunctionalResultSha256)
                    .describedAs("SOLUTION 전역 고유 문제 최초 성공 정책")
                    .isNotEqualTo(measurement.legacyFunctionalResultSha256)
            } else {
                assertThat(measurement.currentFunctionalResultSha256)
                    .describedAs("${measurement.dataType} 현재 서비스 기능 결과")
                    .isEqualTo(measurement.legacyFunctionalResultSha256)
            }
            assertThat(measurement.currentFunctionalResultSha256)
                .describedAs("${measurement.dataType} 현재 정책 기준 결과")
                .isEqualTo(measurement.expectedCurrentFunctionalResultSha256)
            assertThat(measurement.aggregateReplayFunctionalResultSha256)
                .describedAs("${measurement.dataType} aggregate raw replay 결과")
                .isEqualTo(measurement.currentFunctionalResultSha256)

            assertThat(measurement.legacy.commands.find)
                .describedAs("${measurement.dataType} 이전 전체 문서 조회 find")
                .isEqualTo(1)
            assertThat(measurement.legacy.commands.aggregate)
                .describedAs("${measurement.dataType} 이전 전체 문서 조회 aggregate")
                .isZero()
            assertThat(measurement.current.commands.find)
                .describedAs("${measurement.dataType} 현재 서비스 find")
                .isZero()
            assertThat(measurement.current.commands.aggregate)
                .describedAs("${measurement.dataType} 현재 서비스 aggregate")
                .isEqualTo(1)

            assertThat(measurement.current.returnedDocumentCount)
                .describedAs("${measurement.dataType} 반환 문서 수")
                .isLessThan(measurement.legacy.returnedDocumentCount)
            assertThat(measurement.current.returnedDocumentBsonBytes)
                .describedAs("${measurement.dataType} 반환 논리 BSON 크기")
                .isLessThan(measurement.legacy.returnedDocumentBsonBytes)
        }

        writeSnapshot(
            AdminDashboardChartBaselineSnapshot(
                database = mongoTemplate.db.name,
                fixture = AdminDashboardChartFixtureSnapshot(
                    students = STUDENT_COUNT,
                    solutionsPerStudent = SOLUTION_COUNT_PER_STUDENT,
                    retrospectives = RETROSPECTIVE_COUNT,
                    retrospectiveContentCharacters = RETROSPECTIVE_CONTENT_CHARACTERS,
                    monthlyBuckets = MONTH_BUCKET_COUNT
                ),
                measurements = measurements
            )
        )
    }

    private fun measure(dataType: ChartDataType): AdminDashboardChartScenarioSnapshot {
        val legacyCapture = capture {
            legacyChart(dataType)
        }
        val legacyFind = legacyCapture.commands.requireSingle("find", dataType.collection)
        val legacyReplay = replayFind(dataType.collection, legacyFind)

        val currentCapture = capture {
            chartService.getChartData(dataType, ChartPeriod.MONTHLY)
        }
        val aggregateCommand = currentCapture.commands.requireSingle("aggregate", dataType.collection)
        val aggregateReplay = replayAggregate(dataType.collection, aggregateCommand)
        val replayedChart = cumulativeChart(aggregateReplay.documents)

        return AdminDashboardChartScenarioSnapshot(
            dataType = dataType.name,
            period = ChartPeriod.MONTHLY.name,
            legacy = AdminDashboardChartReadPathSnapshot(
                commands = legacyCapture.commands.counts(dataType.collection),
                returnedDocumentCount = legacyReplay.documentCount,
                returnedDocumentBsonBytes = legacyReplay.documentBsonBytes,
                commandShape = AdminDashboardChartCommandShapeSnapshot(
                    filter = legacyFind.filter?.toJson(),
                    projection = legacyFind.projection?.toJson(),
                    pipeline = null
                )
            ),
            current = AdminDashboardChartReadPathSnapshot(
                commands = currentCapture.commands.counts(dataType.collection),
                returnedDocumentCount = aggregateReplay.documentCount,
                returnedDocumentBsonBytes = aggregateReplay.documentBsonBytes,
                commandShape = AdminDashboardChartCommandShapeSnapshot(
                    filter = null,
                    projection = null,
                    pipeline = aggregateCommand.pipeline?.toString()
                )
            ),
            legacyFunctionalResultSha256 = sha256(legacyCapture.value),
            expectedCurrentFunctionalResultSha256 = sha256(expectedCurrentChart(dataType)),
            currentFunctionalResultSha256 = sha256(currentCapture.value),
            aggregateReplayFunctionalResultSha256 = sha256(replayedChart)
        )
    }

    private fun <T> capture(action: () -> T): AdminDashboardChartCapturedValue<T> {
        commandRecorder.enableAndReset()
        val value = action()
        return AdminDashboardChartCapturedValue(
            value = value,
            commands = commandRecorder.disableAndSnapshot()
        )
    }

    private fun legacyChart(dataType: ChartDataType): List<ChartDataPoint> {
        return when (dataType) {
            ChartDataType.USER -> {
                cumulative(
                    studentRepository.findAll()
                        .groupingBy { student -> student.createdAt.format(monthFormatter) }
                        .eachCount()
                )
            }
            ChartDataType.SOLUTION -> {
                val counts = mutableMapOf<String, MutableSet<String>>()
                studentRepository.findAll().forEach { student ->
                    student.solutions.getAll()
                        .filter(Solution::isSuccess)
                        .forEach { solution ->
                            val month = solution.solvedAt.format(monthFormatter)
                            counts.getOrPut(month, ::mutableSetOf).add(solution.problemId.value)
                        }
                }
                cumulative(counts.mapValues { (_, problemIds) -> problemIds.size })
            }
            ChartDataType.RETROSPECTIVE -> {
                cumulative(
                    retrospectiveRepository.findAll()
                        .groupingBy { retrospective -> retrospective.createdAt.format(monthFormatter) }
                        .eachCount()
                )
            }
        }
    }

    private fun expectedCurrentChart(dataType: ChartDataType): List<ChartDataPoint> {
        if (dataType != ChartDataType.SOLUTION) {
            return legacyChart(dataType)
        }

        val firstSuccessByProblem = mutableMapOf<String, LocalDateTime>()
        studentRepository.findAll().forEach { student ->
            student.solutions.getAll()
                .filter(Solution::isSuccess)
                .forEach { solution ->
                    firstSuccessByProblem.merge(
                        solution.problemId.value,
                        solution.solvedAt
                    ) { current, candidate ->
                        minOf(current, candidate)
                    }
                }
        }
        return cumulative(
            firstSuccessByProblem.values
                .groupingBy { solvedAt -> solvedAt.format(monthFormatter) }
                .eachCount()
        )
    }

    private fun cumulative(counts: Map<String, Int>): List<ChartDataPoint> {
        var total = 0L
        return counts.toSortedMap().map { (date, count) ->
            total += count
            ChartDataPoint(date = date, value = total)
        }
    }

    private fun cumulativeChart(documents: List<RawBsonDocument>): List<ChartDataPoint> {
        var total = 0L
        return documents.map { document ->
            total += document.getNumber("count").longValue()
            ChartDataPoint(
                date = document.getString("_id").value,
                value = total
            )
        }
    }

    private fun replayFind(
        collection: String,
        command: AdminDashboardChartBaselineObservedCommand
    ): AdminDashboardChartRawReplay {
        val find = mongoTemplate.db
            .getCollection(collection, RawBsonDocument::class.java)
            .find(command.filter ?: BsonDocument())
        command.projection?.let(find::projection)

        return collectRaw(find)
    }

    private fun replayAggregate(
        collection: String,
        command: AdminDashboardChartBaselineObservedCommand
    ): AdminDashboardChartRawReplay {
        val pipeline = requireNotNull(command.pipeline) {
            "$collection aggregate command에 pipeline이 없습니다."
        }.values.map { stage ->
            Document.parse(stage.asDocument().toJson())
        }
        val aggregate = mongoTemplate.db
            .getCollection(collection, RawBsonDocument::class.java)
            .aggregate(pipeline)

        return collectRaw(aggregate)
    }

    private fun collectRaw(documents: Iterable<RawBsonDocument>): AdminDashboardChartRawReplay {
        val captured = documents.toList()
        return AdminDashboardChartRawReplay(
            documents = captured,
            documentCount = captured.size.toLong(),
            documentBsonBytes = captured.sumOf { document ->
                document.byteBuffer.remaining().toLong()
            }
        )
    }

    private fun seedFixture() {
        studentRepository.saveAll(
            (0 until STUDENT_COUNT).map(::student)
        )
        retrospectiveRepository.saveAll(
            (0 until RETROSPECTIVE_COUNT).map(::retrospective)
        )
    }

    private fun student(index: Int): Student {
        val solutions = (0 until SOLUTION_COUNT_PER_STUDENT).map { solutionIndex ->
            val repeatedInLastMonth = solutionIndex == SOLUTION_COUNT_PER_STUDENT - 1 &&
                index < SHARED_PROBLEM_COUNT
            val sharedBoundarySolution = solutionIndex == 0 || repeatedInLastMonth
            val result = if (sharedBoundarySolution) {
                ProblemResult.SUCCESS
            } else if ((index + solutionIndex) % 5 == 0) {
                ProblemResult.FAIL
            } else {
                ProblemResult.SUCCESS
            }
            Solution(
                problemId = ProblemId(
                    if (sharedBoundarySolution) {
                        "chart-shared-%03d".format(index % SHARED_PROBLEM_COUNT)
                    } else {
                        "chart-$index-$solutionIndex"
                    }
                ),
                timeTaken = TimeTakenSeconds(60),
                result = result,
                solvedAt = FIXTURE_START.plusMonths(solutionIndex.toLong())
            )
        }
        return Student(
            id = "admin-chart-baseline-student-$index",
            nickname = Nickname("chart%04d".format(index)),
            provider = Provider.BOJ,
            providerId = "admin-chart-baseline-provider-$index",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            solutions = Solutions(solutions.toMutableList()),
            createdAt = FIXTURE_START.plusMonths((index % MONTH_BUCKET_COUNT).toLong())
        )
    }

    private fun retrospective(index: Int): Retrospective {
        val studentIndex = index % STUDENT_COUNT
        return Retrospective(
            studentId = "admin-chart-baseline-student-$studentIndex",
            problemId = "admin-chart-baseline-retrospective-$index",
            content = RETROSPECTIVE_CONTENT,
            createdAt = FIXTURE_START.plusMonths((index % MONTH_BUCKET_COUNT).toLong())
        )
    }

    private fun sha256(value: Any): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsBytes(value))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun writeSnapshot(snapshot: AdminDashboardChartBaselineSnapshot) {
        val outputDirectory = System.getenv("ADMIN_DASHBOARD_CHART_BASELINE_OUTPUT_DIR")
            ?.takeIf(String::isNotBlank)
            ?: return
        val directory = Path.of(outputDirectory)
        Files.createDirectories(directory)
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(directory.resolve(SNAPSHOT_FILE_NAME).toFile(), snapshot)
    }

    private val ChartDataType.collection: String
        get() = when (this) {
            ChartDataType.USER, ChartDataType.SOLUTION -> STUDENT_COLLECTION
            ChartDataType.RETROSPECTIVE -> RETROSPECTIVE_COLLECTION
        }

    companion object {
        private const val STUDENT_COUNT = 240
        private const val SOLUTION_COUNT_PER_STUDENT = 24
        private const val SHARED_PROBLEM_COUNT = 120
        private const val RETROSPECTIVE_COUNT = 1_200
        private const val RETROSPECTIVE_CONTENT_CHARACTERS = 4_096
        private const val MONTH_BUCKET_COUNT = 24
        private const val STUDENT_COLLECTION = "students"
        private const val RETROSPECTIVE_COLLECTION = "retrospectives"
        private const val SNAPSHOT_FILE_NAME = "admin-dashboard-chart-baseline.json"
        private val FIXTURE_START = LocalDateTime.of(2022, 1, 15, 12, 0)
        private val RETROSPECTIVE_CONTENT = "r".repeat(RETROSPECTIVE_CONTENT_CHARACTERS)
    }
}

@TestConfiguration
class AdminDashboardChartBaselineMongoConfiguration {

    @Bean
    fun adminDashboardChartBaselineCommandRecorder(): AdminDashboardChartBaselineCommandRecorder {
        return AdminDashboardChartBaselineCommandRecorder()
    }

    @Bean
    fun adminDashboardChartBaselineMongoCustomizer(
        recorder: AdminDashboardChartBaselineCommandRecorder
    ): MongoClientSettingsBuilderCustomizer {
        return MongoClientSettingsBuilderCustomizer { builder: MongoClientSettings.Builder ->
            builder.addCommandListener(recorder)
        }
    }
}

class AdminDashboardChartBaselineCommandRecorder : CommandListener {
    private val commands = ConcurrentLinkedQueue<AdminDashboardChartBaselineObservedCommand>()

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

        commands.add(
            AdminDashboardChartBaselineObservedCommand(
                name = event.commandName,
                collection = collection,
                filter = (event.command["filter"] as? BsonDocument)?.clone(),
                projection = (event.command["projection"] as? BsonDocument)?.clone(),
                pipeline = (event.command["pipeline"] as? BsonArray)?.clone()
            )
        )
    }

    fun enableAndReset() {
        commands.clear()
        enabled = true
    }

    fun disableAndReset() {
        enabled = false
        commands.clear()
    }

    fun disableAndSnapshot(): List<AdminDashboardChartBaselineObservedCommand> {
        enabled = false
        val snapshot = commands.toList()
        commands.clear()
        return snapshot
    }

    companion object {
        private val TRACKED_COMMANDS = setOf("find", "aggregate", "getMore")
        private val TRACKED_COLLECTIONS = setOf("students", "retrospectives")
    }
}

data class AdminDashboardChartBaselineObservedCommand(
    val name: String,
    val collection: String,
    val filter: BsonDocument?,
    val projection: BsonDocument?,
    val pipeline: BsonArray?
)

private fun List<AdminDashboardChartBaselineObservedCommand>.requireSingle(
    name: String,
    collection: String
): AdminDashboardChartBaselineObservedCommand {
    val matches = filter { command ->
        command.name == name && command.collection == collection
    }
    check(matches.size == 1) {
        "$collection $name command가 정확히 1개가 아닙니다. commands=$this"
    }
    return matches.single()
}

private fun List<AdminDashboardChartBaselineObservedCommand>.counts(
    collection: String
): AdminDashboardChartCommandCountSnapshot {
    return AdminDashboardChartCommandCountSnapshot(
        find = count { command -> command.name == "find" && command.collection == collection },
        aggregate = count { command -> command.name == "aggregate" && command.collection == collection },
        getMore = count { command -> command.name == "getMore" && command.collection == collection }
    )
}

data class AdminDashboardChartCapturedValue<T>(
    val value: T,
    val commands: List<AdminDashboardChartBaselineObservedCommand>
)

data class AdminDashboardChartRawReplay(
    val documents: List<RawBsonDocument>,
    val documentCount: Long,
    val documentBsonBytes: Long
)

data class AdminDashboardChartBaselineSnapshot(
    val database: String,
    val fixture: AdminDashboardChartFixtureSnapshot,
    val measurements: List<AdminDashboardChartScenarioSnapshot>
)

data class AdminDashboardChartFixtureSnapshot(
    val students: Int,
    val solutionsPerStudent: Int,
    val retrospectives: Int,
    val retrospectiveContentCharacters: Int,
    val monthlyBuckets: Int
)

data class AdminDashboardChartScenarioSnapshot(
    val dataType: String,
    val period: String,
    val legacy: AdminDashboardChartReadPathSnapshot,
    val current: AdminDashboardChartReadPathSnapshot,
    val legacyFunctionalResultSha256: String,
    val expectedCurrentFunctionalResultSha256: String,
    val currentFunctionalResultSha256: String,
    val aggregateReplayFunctionalResultSha256: String
)

data class AdminDashboardChartReadPathSnapshot(
    val commands: AdminDashboardChartCommandCountSnapshot,
    val returnedDocumentCount: Long,
    val returnedDocumentBsonBytes: Long,
    val commandShape: AdminDashboardChartCommandShapeSnapshot
)

data class AdminDashboardChartCommandCountSnapshot(
    val find: Int,
    val aggregate: Int,
    val getMore: Int
)

data class AdminDashboardChartCommandShapeSnapshot(
    val filter: String?,
    val projection: String?,
    val pipeline: String?
)
