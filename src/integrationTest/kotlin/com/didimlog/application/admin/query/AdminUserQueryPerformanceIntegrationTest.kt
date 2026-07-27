package com.didimlog.application.admin.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.didimlog.application.admin.AdminService
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.config.PasswordEncoderConfig
import com.didimlog.global.config.mongo.MongoIndexInitializer
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonNumber
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.test.context.ActiveProfiles

@DisplayName("관리자 회원 목록 조회 command 성능 회귀")
@DataMongoTest
@ActiveProfiles("test")
@Import(
    AdminService::class,
    PasswordEncoderConfig::class,
    MongoIndexInitializer::class,
    AdminQueryMongoCommandConfiguration::class
)
class AdminUserQueryPerformanceIntegrationTest {

    @Autowired
    private lateinit var adminService: AdminService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var commandCounter: MongoCommandCounter

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var mongoIndexInitializer: MongoIndexInitializer

    private val queryPlanExplainer by lazy {
        MongoQueryPlanExplainer(mongoTemplate)
    }

    @BeforeEach
    fun setUp() {
        retrospectiveRepository.deleteAll()
        studentRepository.deleteAll()

        val students = studentRepository.saveAll(
            (1..TOTAL_STUDENT_COUNT).map(::createStudent)
        )
        retrospectiveRepository.saveAll(
            students.mapIndexed { index, student ->
                Retrospective(
                    studentId = requireNotNull(student.id),
                    problemId = "baseline-problem-$index",
                    content = "관리자 조회 baseline 회고 내용 $index"
                )
            }
        )

        commandCounter.reset()
    }

    @AfterEach
    fun tearDown() {
        retrospectiveRepository.deleteAll()
        studentRepository.deleteAll()
        commandCounter.reset()
    }

    @ParameterizedTest(name = "page size {0}이어도 Mongo read command는 3번 실행된다")
    @ValueSource(ints = [1, 5, 10])
    fun `회원 목록 조회는 회고 수를 한 번에 집계한다`(pageSize: Int) {
        val result = adminService.getAllUsers(
            PageRequest.of(
                0,
                pageSize,
                Sort.by(Sort.Direction.DESC, "rating")
            )
        )

        assertThat(result.content).hasSize(pageSize)
        assertThat(result.totalElements).isEqualTo(TOTAL_STUDENT_COUNT.toLong())
        assertThat(result.content.first().id).isEqualTo("baseline-student-12")
        assertThat(result.content).allSatisfy { user ->
            assertThat(user.solvedCount).isZero()
            assertThat(user.retrospectiveCount).isEqualTo(1)
        }

        val studentFindCount = commandCounter.countCommands("find", "students")
        val studentAggregateCount = commandCounter.countCommands("aggregate", "students")
        val retrospectiveFindCount = commandCounter.countCommands("find", "retrospectives")
        val retrospectiveAggregateCount = commandCounter.countCommands("aggregate", "retrospectives")
        val totalReadCommandCount = commandCounter.countReadCommands()
        val studentFindCommand = commandCounter.requireSingleFindCommand("students")
        val studentCountPipeline = commandCounter.requireSingleAggregatePipeline("students")
        val retrospectiveAggregatePipeline = commandCounter.requireSingleAggregatePipeline("retrospectives")

        assertThat(studentFindCount)
            .describedAs("페이지 회원 조회 command")
            .isEqualTo(1)
        assertThat(studentAggregateCount)
            .describedAs("필터된 회원 전체 수 count command")
            .isEqualTo(1)
        assertThat(retrospectiveFindCount)
            .describedAs("페이지 회원별 회고 조회 command")
            .isZero()
        assertThat(retrospectiveAggregateCount)
            .describedAs("페이지 회원 회고 수 batch 집계 command")
            .isEqualTo(1)
        assertThat(totalReadCommandCount)
            .describedAs("관리자 회원 목록의 고정 Mongo read command")
            .isEqualTo(3)

        val studentIndexes = queryPlanExplainer.collectIndexes(Student::class.java)
        val retrospectiveIndexes = queryPlanExplainer.collectIndexes(Retrospective::class.java)
        val studentPageStats = queryPlanExplainer.explainFind(
            query = "searchAdminUsersPage",
            command = studentFindCommand
        )
        val studentCountStats = queryPlanExplainer.explainAggregation(
            collection = "students",
            query = "searchAdminUsersCount",
            pipeline = studentCountPipeline
        )
        val retrospectiveCountByStudentIdsStats = queryPlanExplainer.explainAggregation(
            collection = "retrospectives",
            query = "countByStudentIds",
            pipeline = retrospectiveAggregatePipeline
        )

        assertQueryPlanBaseline(
            studentIndexes = studentIndexes,
            retrospectiveIndexes = retrospectiveIndexes,
            studentPageStats = studentPageStats,
            studentCountStats = studentCountStats,
            retrospectiveCountByStudentIdsStats = retrospectiveCountByStudentIdsStats,
            expectedPageSize = pageSize.toLong(),
            expectedGroupedStudentCount = result.content.count { it.retrospectiveCount > 0 }.toLong()
        )

        writeSnapshotIfRequested(
            AdminQueryMeasurementSnapshot(
                source = BaselineSource(
                    commitSha = System.getenv("ADMIN_QUERY_BASELINE_COMMIT_SHA") ?: "NOT_CAPTURED",
                    gitDirty = System.getenv("ADMIN_QUERY_BASELINE_GIT_DIRTY")?.toBooleanStrictOrNull()
                ),
                database = mongoTemplate.db.name,
                pageSize = pageSize,
                commandCounts = MongoCommandCounts(
                    studentFind = studentFindCount,
                    studentAggregate = studentAggregateCount,
                    retrospectiveFind = retrospectiveFindCount,
                    retrospectiveAggregate = retrospectiveAggregateCount,
                    totalRead = totalReadCommandCount
                ),
                studentIndexes = studentIndexes,
                retrospectiveIndexes = retrospectiveIndexes,
                studentPage = studentPageStats,
                studentCount = studentCountStats,
                retrospectiveCountByStudentIds = retrospectiveCountByStudentIdsStats
            )
        )
    }

    @Test
    fun `빈 회원 목록은 회고 집계 command를 실행하지 않는다`() {
        val result = adminService.getAllUsers(
            pageable = PageRequest.of(0, 5),
            search = "no-matching-student"
        )

        assertThat(result.content).isEmpty()
        assertThat(result.totalElements).isZero()
        assertThat(commandCounter.countCommands("find", "students")).isZero()
        assertThat(commandCounter.countCommands("aggregate", "students")).isEqualTo(1)
        assertThat(commandCounter.countCommands("find", "retrospectives")).isZero()
        assertThat(commandCounter.countCommands("aggregate", "retrospectives")).isZero()
        assertThat(commandCounter.countReadCommands()).isEqualTo(1)
    }

    @Test
    fun `회고 studentId 단일 인덱스를 보장한다`() {
        val retrospectiveIndexes = queryPlanExplainer.collectIndexes(Retrospective::class.java)

        assertThat(retrospectiveIndexes.map { it.name })
            .containsExactly("_id_", RETROSPECTIVE_STUDENT_ID_INDEX_NAME)

        val studentIdIndex = requireNotNull(
            retrospectiveIndexes.singleOrNull { it.name == RETROSPECTIVE_STUDENT_ID_INDEX_NAME }
        )
        assertThat(studentIdIndex.unique).isFalse()
        assertThat(studentIdIndex.sparse).isFalse()
        assertThat(studentIdIndex.fields)
            .containsExactly(
                MongoIndexFieldBaseline(
                    key = "studentId",
                    direction = "ASC"
                )
            )
    }

    @Test
    fun `회고 studentId 인덱스 초기화는 반복 실행해도 중복되지 않는다`() {
        mongoIndexInitializer.ensureIndexes()
        mongoIndexInitializer.ensureIndexes()

        assertThat(
            queryPlanExplainer.collectIndexes(Retrospective::class.java)
                .count { it.name == RETROSPECTIVE_STUDENT_ID_INDEX_NAME }
        ).isEqualTo(1)
    }

    @Test
    fun `회고 studentId 인덱스는 기존 이름이 달라도 재사용한다`() {
        val indexOperations = mongoTemplate.indexOps(Retrospective::class.java)
        indexOperations.dropIndex(RETROSPECTIVE_STUDENT_ID_INDEX_NAME)
        indexOperations.ensureIndex(
            Index()
                .on("studentId", Sort.Direction.ASC)
                .named(LEGACY_STUDENT_ID_INDEX_NAME)
        )

        try {
            mongoIndexInitializer.ensureIndexes()

            assertThat(queryPlanExplainer.collectIndexes(Retrospective::class.java).map { it.name })
                .containsExactly("_id_", LEGACY_STUDENT_ID_INDEX_NAME)
        } finally {
            indexOperations.dropIndex(LEGACY_STUDENT_ID_INDEX_NAME)
            mongoIndexInitializer.ensureIndexes()
        }
    }

    private fun assertQueryPlanBaseline(
        studentIndexes: List<MongoIndexBaseline>,
        retrospectiveIndexes: List<MongoIndexBaseline>,
        studentPageStats: MongoQueryExecutionBaseline,
        studentCountStats: MongoQueryExecutionBaseline,
        retrospectiveCountByStudentIdsStats: MongoQueryExecutionBaseline,
        expectedPageSize: Long,
        expectedGroupedStudentCount: Long
    ) {
        assertThat(studentIndexes.map { it.name }).contains("_id_")
        assertThat(retrospectiveIndexes.map { it.name })
            .containsExactly("_id_", RETROSPECTIVE_STUDENT_ID_INDEX_NAME)

        assertThat(studentPageStats.winningPlanStage).isIn("COLLSCAN", "IXSCAN")
        assertThat(studentPageStats.nReturned).isEqualTo(expectedPageSize)
        assertThat(studentPageStats.totalDocsExamined)
            .isBetween(expectedPageSize, TOTAL_STUDENT_COUNT.toLong())
        assertThat(studentPageStats.totalKeysExamined)
            .isBetween(0L, expectedPageSize)

        assertThat(studentCountStats.winningPlanStage)
            .isIn("COLLSCAN", "IXSCAN", "COUNT_SCAN")
        assertThat(studentCountStats.nReturned).isEqualTo(1)
        assertThat(studentCountStats.totalDocsExamined)
            .isBetween(0L, TOTAL_STUDENT_COUNT.toLong())
        assertThat(studentCountStats.totalKeysExamined)
            .isBetween(0L, TOTAL_STUDENT_COUNT.toLong())

        assertThat(retrospectiveCountByStudentIdsStats.winningPlanStage).isEqualTo("IXSCAN")
        assertThat(retrospectiveCountByStudentIdsStats.selectedIndexName)
            .isEqualTo(RETROSPECTIVE_STUDENT_ID_INDEX_NAME)
        assertThat(retrospectiveCountByStudentIdsStats.selectedIndexKeyPattern)
            .containsExactlyEntriesOf(mapOf("studentId" to 1))
        assertThat(retrospectiveCountByStudentIdsStats.nReturned).isEqualTo(expectedGroupedStudentCount)
        assertThat(retrospectiveCountByStudentIdsStats.totalDocsExamined).isZero()
        assertThat(retrospectiveCountByStudentIdsStats.totalKeysExamined)
            .isEqualTo(expectedGroupedStudentCount)
        assertThat(retrospectiveCountByStudentIdsStats.hasBlockingSort).isFalse()
    }

    private fun writeSnapshotIfRequested(snapshot: AdminQueryMeasurementSnapshot) {
        val configuredOutputDirectory = System.getenv(OUTPUT_DIRECTORY_ENV)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val outputDirectory = Path.of(configuredOutputDirectory).toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)
        val outputFile = outputDirectory.resolve(
            "admin-users-page-size-%02d.json".format(snapshot.pageSize)
        )

        jacksonObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValue(outputFile.toFile(), snapshot)
    }

    private fun createStudent(index: Int): Student {
        return Student(
            id = "baseline-student-$index",
            nickname = Nickname("query$index"),
            provider = Provider.BOJ,
            providerId = "baseline-provider-$index",
            bojId = BojId("baseline_$index"),
            password = "encoded-password",
            rating = 1_000 + index,
            currentTier = Tier.GOLD,
            role = Role.USER
        )
    }

    companion object {
        private const val TOTAL_STUDENT_COUNT = 12
        private const val OUTPUT_DIRECTORY_ENV = "ADMIN_QUERY_BASELINE_OUTPUT_DIR"
        private const val RETROSPECTIVE_STUDENT_ID_INDEX_NAME = "studentId"
        private const val LEGACY_STUDENT_ID_INDEX_NAME = "studentId_1"
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AdminQueryMongoCommandConfiguration {

    @Bean
    fun mongoCommandCounter(): MongoCommandCounter = MongoCommandCounter()

    @Bean
    fun mongoCommandCounterCustomizer(
        commandCounter: MongoCommandCounter
    ): MongoClientSettingsBuilderCustomizer {
        return MongoClientSettingsBuilderCustomizer { builder: MongoClientSettings.Builder ->
            builder.addCommandListener(commandCounter)
        }
    }
}

class MongoCommandCounter : CommandListener {

    private val readCommands = ConcurrentLinkedQueue<ObservedMongoReadCommand>()

    override fun commandStarted(event: CommandStartedEvent) {
        if (event.commandName !in TRACKED_READ_COMMANDS) {
            return
        }

        val collectionField = if (event.commandName == "getMore") {
            "collection"
        } else {
            event.commandName
        }
        val collection = (event.command[collectionField] as? BsonString)?.value ?: return
        if (collection != "students" && collection != "retrospectives") {
            return
        }

        val pipeline = event.command["pipeline"] as? BsonArray
        readCommands.add(
            ObservedMongoReadCommand(
                command = event.commandName,
                collection = collection,
                filter = (event.command["filter"] as? BsonDocument)
                    ?.let { Document.parse(it.toJson()) },
                filterJson = (event.command["filter"] as? BsonDocument)?.toJson()
                    ?: (event.command["query"] as? BsonDocument)?.toJson(),
                sort = (event.command["sort"] as? BsonDocument)
                    ?.let { Document.parse(it.toJson()) },
                sortJson = (event.command["sort"] as? BsonDocument)?.toJson(),
                skip = (event.command["skip"] as? BsonNumber)?.longValue(),
                limit = (event.command["limit"] as? BsonNumber)?.longValue(),
                batchSize = (event.command["batchSize"] as? BsonNumber)?.longValue(),
                pipelineJson = pipeline?.toString(),
                pipeline = pipeline
                    ?.values
                    ?.map { stage -> Document.parse(stage.asDocument().toJson()) }
            )
        )
    }

    fun reset() {
        readCommands.clear()
    }

    fun countCommands(command: String, collection: String): Int {
        return readCommands.count { observed ->
            observed.command == command && observed.collection == collection
        }
    }

    fun countReadCommands(): Int = readCommands.size

    fun studentReadCommands(): List<ObservedMongoReadCommand> {
        return readCommands.filter { observed -> observed.collection == "students" }
    }

    fun requireSingleFindCommand(collection: String): ObservedMongoReadCommand {
        val commands = readCommands.filter { observed ->
            observed.command == "find" && observed.collection == collection
        }
        check(commands.size == 1) {
            "$collection find command가 정확히 1개가 아닙니다. commands=$commands"
        }
        return commands.single()
    }

    fun requireSingleAggregatePipeline(collection: String): List<Document> {
        val commands = readCommands.filter { observed ->
            observed.command == "aggregate" && observed.collection == collection
        }
        check(commands.size == 1) {
            "$collection aggregate command가 정확히 1개가 아닙니다. commands=$commands"
        }
        return requireNotNull(commands.single().pipeline) {
            "$collection aggregate command에 pipeline이 없습니다."
        }
    }

    companion object {
        private val TRACKED_READ_COMMANDS = setOf("find", "aggregate", "count", "getMore")
    }
}

data class ObservedMongoReadCommand(
    val command: String,
    val collection: String,
    val filter: Document?,
    val filterJson: String?,
    val sort: Document?,
    val sortJson: String?,
    val skip: Long?,
    val limit: Long?,
    val batchSize: Long?,
    val pipelineJson: String?,
    val pipeline: List<Document>?
)

data class AdminQueryMeasurementSnapshot(
    val source: BaselineSource,
    val database: String,
    val pageSize: Int,
    val commandCounts: MongoCommandCounts,
    val studentIndexes: List<MongoIndexBaseline>,
    val retrospectiveIndexes: List<MongoIndexBaseline>,
    val studentPage: MongoQueryExecutionBaseline,
    val studentCount: MongoQueryExecutionBaseline,
    val retrospectiveCountByStudentIds: MongoQueryExecutionBaseline
)

data class BaselineSource(
    val commitSha: String,
    val gitDirty: Boolean?
)

data class MongoCommandCounts(
    val studentFind: Int,
    val studentAggregate: Int,
    val retrospectiveFind: Int,
    val retrospectiveAggregate: Int,
    val totalRead: Int
)

data class MongoIndexBaseline(
    val name: String?,
    val unique: Boolean,
    val sparse: Boolean,
    val fields: List<MongoIndexFieldBaseline>
)

data class MongoIndexFieldBaseline(
    val key: String,
    val direction: String?
)

data class MongoQueryExecutionBaseline(
    val collection: String,
    val query: String,
    val winningPlanStage: String,
    val selectedIndexName: String?,
    val selectedIndexKeyPattern: Map<String, Int>?,
    val hasBlockingSort: Boolean,
    @get:JsonProperty("nReturned")
    val nReturned: Long,
    val totalDocsExamined: Long,
    val totalKeysExamined: Long
)
