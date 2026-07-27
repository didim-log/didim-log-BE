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
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles

@DisplayName("관리자 회원 목록 조회 command 성능 회귀")
@DataMongoTest
@ActiveProfiles("test")
@Import(
    AdminService::class,
    PasswordEncoderConfig::class,
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

    @ParameterizedTest(name = "page size {0}이어도 Mongo read command는 2번 실행된다")
    @ValueSource(ints = [1, 5, 10])
    fun `회원 목록 조회는 회고 수를 한 번에 집계한다`(pageSize: Int) {
        val result = adminService.getAllUsers(PageRequest.of(0, pageSize))

        assertThat(result.content).hasSize(pageSize)
        assertThat(result.totalElements).isEqualTo(TOTAL_STUDENT_COUNT.toLong())
        assertThat(result.content).allSatisfy { user ->
            assertThat(user.solvedCount).isZero()
            assertThat(user.retrospectiveCount).isEqualTo(1)
        }

        val studentFindCount = commandCounter.countCommands("find", "students")
        val retrospectiveFindCount = commandCounter.countCommands("find", "retrospectives")
        val retrospectiveAggregateCount = commandCounter.countCommands("aggregate", "retrospectives")
        val totalReadCommandCount = commandCounter.countReadCommands()

        assertThat(studentFindCount)
            .describedAs("회원 전체 조회 command")
            .isEqualTo(1)
        assertThat(retrospectiveFindCount)
            .describedAs("페이지 회원별 회고 조회 command")
            .isZero()
        assertThat(retrospectiveAggregateCount)
            .describedAs("페이지 회원 회고 수 batch 집계 command")
            .isEqualTo(1)
        assertThat(totalReadCommandCount)
            .describedAs("관리자 회원 목록의 고정 Mongo read command")
            .isEqualTo(2)

        val studentIndexes = collectIndexes(Student::class.java)
        val retrospectiveIndexes = collectIndexes(Retrospective::class.java)
        val studentFindAllStats = explainFind(
            collection = "students",
            query = "findAll",
            filter = Document()
        )
        val retrospectiveCountByStudentIdsStats = explainRetrospectiveCountAggregation(
            studentIds = result.content.map { it.id }
        )

        assertQueryPlanBaseline(
            studentIndexes = studentIndexes,
            retrospectiveIndexes = retrospectiveIndexes,
            studentFindAllStats = studentFindAllStats,
            retrospectiveCountByStudentIdsStats = retrospectiveCountByStudentIdsStats,
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
                    retrospectiveFind = retrospectiveFindCount,
                    retrospectiveAggregate = retrospectiveAggregateCount,
                    totalRead = totalReadCommandCount
                ),
                studentIndexes = studentIndexes,
                retrospectiveIndexes = retrospectiveIndexes,
                studentFindAll = studentFindAllStats,
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
        assertThat(commandCounter.countCommands("find", "students")).isEqualTo(1)
        assertThat(commandCounter.countCommands("find", "retrospectives")).isZero()
        assertThat(commandCounter.countCommands("aggregate", "retrospectives")).isZero()
        assertThat(commandCounter.countReadCommands()).isEqualTo(1)
    }

    private fun collectIndexes(entityType: Class<*>): List<MongoIndexBaseline> {
        return mongoTemplate.indexOps(entityType).indexInfo
            .map { index ->
                MongoIndexBaseline(
                    name = index.name,
                    unique = index.isUnique || index.name == "_id_",
                    sparse = index.isSparse,
                    fields = index.indexFields.map { field ->
                        MongoIndexFieldBaseline(
                            key = field.key,
                            direction = field.direction?.name
                        )
                    }
                )
            }
            .sortedBy { it.name }
    }

    private fun explainFind(
        collection: String,
        query: String,
        filter: Document
    ): MongoQueryExecutionBaseline {
        val explain = mongoTemplate.executeCommand(
            Document(
                "explain",
                Document("find", collection)
                    .append("filter", filter)
            ).append("verbosity", "executionStats")
        )
        val queryPlanner = explain.requiredDocument("queryPlanner")
        val winningPlan = queryPlanner.requiredDocument("winningPlan")
        val executionStats = explain.requiredDocument("executionStats")
        val accessPlan = requireNotNull(findAccessPlan(winningPlan)) {
            "winning access plan을 찾을 수 없습니다. winningPlan=$winningPlan"
        }

        return MongoQueryExecutionBaseline(
            collection = collection,
            query = query,
            winningPlanStage = accessPlan.requiredString("stage"),
            selectedIndexName = accessPlan.getString("indexName"),
            selectedIndexKeyPattern = accessPlan.indexKeyPattern(),
            nReturned = executionStats.requiredLong("nReturned"),
            totalDocsExamined = executionStats.requiredLong("totalDocsExamined"),
            totalKeysExamined = executionStats.requiredLong("totalKeysExamined")
        )
    }

    private fun explainRetrospectiveCountAggregation(
        studentIds: List<String>
    ): MongoQueryExecutionBaseline {
        val pipeline = listOf(
            Document(
                "\$match",
                Document("studentId", Document("\$in", studentIds))
            ),
            Document(
                "\$group",
                Document("_id", "\$studentId")
                    .append("retrospectiveCount", Document("\$sum", 1))
            ),
            Document(
                "\$project",
                Document("_id", 0)
                    .append("studentId", "\$_id")
                    .append("retrospectiveCount", 1)
            )
        )
        val explain = mongoTemplate.executeCommand(
            Document(
                "explain",
                Document("aggregate", "retrospectives")
                    .append("pipeline", pipeline)
                    .append("cursor", Document())
            ).append("verbosity", "executionStats")
        )
        val cursor = findAggregationCursor(explain)
        val queryPlanner = cursor?.requiredDocument("queryPlanner")
            ?: requireNotNull(findNestedDocument(explain, "queryPlanner")) {
                "Mongo aggregate explain에 queryPlanner가 없습니다. response=$explain"
            }
        val winningPlan = queryPlanner.requiredDocument("winningPlan")
        val executionStats = cursor?.requiredDocument("executionStats")
            ?: requireNotNull(findNestedDocument(explain, "executionStats")) {
                "Mongo aggregate explain에 executionStats가 없습니다. response=$explain"
            }
        val accessPlan = requireNotNull(findAccessPlan(winningPlan)) {
            "winning access plan을 찾을 수 없습니다. winningPlan=$winningPlan"
        }

        return MongoQueryExecutionBaseline(
            collection = "retrospectives",
            query = "countByStudentIds",
            winningPlanStage = accessPlan.requiredString("stage"),
            selectedIndexName = accessPlan.getString("indexName"),
            selectedIndexKeyPattern = accessPlan.indexKeyPattern(),
            nReturned = executionStats.requiredLong("nReturned"),
            totalDocsExamined = executionStats.requiredLong("totalDocsExamined"),
            totalKeysExamined = executionStats.requiredLong("totalKeysExamined")
        )
    }

    private fun findAggregationCursor(explain: Document): Document? {
        return (explain["stages"] as? Iterable<*>)
            ?.asSequence()
            ?.mapNotNull { stage ->
                (stage as? Document)?.get("\$cursor") as? Document
            }
            ?.firstOrNull()
    }

    private fun findNestedDocument(value: Any?, key: String): Document? {
        return when (value) {
            is Document -> {
                (value[key] as? Document)
                    ?: value.values.asSequence()
                        .mapNotNull { nested -> findNestedDocument(nested, key) }
                        .firstOrNull()
            }
            is Iterable<*> -> value.asSequence()
                .mapNotNull { nested -> findNestedDocument(nested, key) }
                .firstOrNull()
            else -> null
        }
    }

    private fun findAccessPlan(value: Any?): Document? {
        return when (value) {
            is Document -> {
                (value["stage"] as? String)
                    ?.takeIf { stage -> stage == "COLLSCAN" || stage == "IXSCAN" }
                    ?.let { value }
                    ?: value.values.asSequence().mapNotNull(::findAccessPlan).firstOrNull()
            }
            is Iterable<*> -> value.asSequence().mapNotNull(::findAccessPlan).firstOrNull()
            else -> null
        }
    }

    private fun assertQueryPlanBaseline(
        studentIndexes: List<MongoIndexBaseline>,
        retrospectiveIndexes: List<MongoIndexBaseline>,
        studentFindAllStats: MongoQueryExecutionBaseline,
        retrospectiveCountByStudentIdsStats: MongoQueryExecutionBaseline,
        expectedGroupedStudentCount: Long
    ) {
        assertThat(studentIndexes.map { it.name }).containsExactly("_id_")
        assertThat(retrospectiveIndexes.map { it.name }).containsExactly("_id_")

        assertThat(studentFindAllStats.winningPlanStage).isEqualTo("COLLSCAN")
        assertThat(studentFindAllStats.selectedIndexName).isNull()
        assertThat(studentFindAllStats.selectedIndexKeyPattern).isNull()
        assertThat(studentFindAllStats.nReturned).isEqualTo(TOTAL_STUDENT_COUNT.toLong())
        assertThat(studentFindAllStats.totalDocsExamined).isEqualTo(TOTAL_STUDENT_COUNT.toLong())
        assertThat(studentFindAllStats.totalKeysExamined).isZero()

        assertThat(retrospectiveCountByStudentIdsStats.winningPlanStage).isEqualTo("COLLSCAN")
        assertThat(retrospectiveCountByStudentIdsStats.selectedIndexName).isNull()
        assertThat(retrospectiveCountByStudentIdsStats.selectedIndexKeyPattern).isNull()
        assertThat(retrospectiveCountByStudentIdsStats.nReturned).isEqualTo(expectedGroupedStudentCount)
        assertThat(retrospectiveCountByStudentIdsStats.totalDocsExamined).isEqualTo(TOTAL_STUDENT_COUNT.toLong())
        assertThat(retrospectiveCountByStudentIdsStats.totalKeysExamined).isZero()
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
        if (event.commandName != "find" && event.commandName != "aggregate") {
            return
        }

        val collection = (event.command[event.commandName] as? BsonString)?.value ?: return
        if (collection != "students" && collection != "retrospectives") {
            return
        }

        readCommands.add(
            ObservedMongoReadCommand(
                command = event.commandName,
                collection = collection
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
}

data class ObservedMongoReadCommand(
    val command: String,
    val collection: String
)

data class AdminQueryMeasurementSnapshot(
    val source: BaselineSource,
    val database: String,
    val pageSize: Int,
    val commandCounts: MongoCommandCounts,
    val studentIndexes: List<MongoIndexBaseline>,
    val retrospectiveIndexes: List<MongoIndexBaseline>,
    val studentFindAll: MongoQueryExecutionBaseline,
    val retrospectiveCountByStudentIds: MongoQueryExecutionBaseline
)

data class BaselineSource(
    val commitSha: String,
    val gitDirty: Boolean?
)

data class MongoCommandCounts(
    val studentFind: Int,
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
    @get:JsonProperty("nReturned")
    val nReturned: Long,
    val totalDocsExamined: Long,
    val totalKeysExamined: Long
)

private fun Document.requiredDocument(key: String): Document {
    return requireNotNull(this[key] as? Document) {
        "Mongo 응답에 $key 문서가 없습니다. response=$this"
    }
}

private fun Document.requiredLong(key: String): Long {
    return requireNotNull(this[key] as? Number) {
        "Mongo 응답에 $key 숫자가 없습니다. document=$this"
    }.toLong()
}

private fun Document.requiredString(key: String): String {
    return requireNotNull(getString(key)) {
        "Mongo 응답에 $key 문자열이 없습니다. document=$this"
    }
}

private fun Document.indexKeyPattern(): Map<String, Int>? {
    val keyPattern = this["keyPattern"] as? Document ?: return null
    return keyPattern.entries.associate { (key, value) ->
        key to requireNotNull(value as? Number) {
            "Mongo index keyPattern 값이 숫자가 아닙니다. key=$key, value=$value"
        }.toInt()
    }
}
