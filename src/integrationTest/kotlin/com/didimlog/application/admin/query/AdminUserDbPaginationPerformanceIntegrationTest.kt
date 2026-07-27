package com.didimlog.application.admin.query

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.didimlog.application.admin.AdminService
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
import com.didimlog.ui.dto.AdminUserResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback
import org.springframework.test.context.ActiveProfiles

@DisplayName("관리자 회원 DB 페이징 전후 성능 계측")
@DataMongoTest
@ActiveProfiles("test")
@Import(
    AdminService::class,
    PasswordEncoderConfig::class,
    MongoIndexInitializer::class,
    AdminQueryMongoCommandConfiguration::class,
    Phase1cAdminQueryMeasurementConfiguration::class
)
class AdminUserDbPaginationPerformanceIntegrationTest {

    @Autowired
    private lateinit var adminService: AdminService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandCounter: MongoCommandCounter

    @Autowired
    private lateinit var materializationCounter: StudentMaterializationCounter

    private val queryPlanExplainer by lazy {
        MongoQueryPlanExplainer(mongoTemplate)
    }

    @BeforeEach
    fun setUp() {
        retrospectiveRepository.deleteAll()
        studentRepository.deleteAll()
        mongoTemplate.insert(
            (1..FIXTURE_STUDENT_COUNT).map(::createStudent),
            Student::class.java
        )
        resetMeasurements()
    }

    @AfterEach
    fun tearDown() {
        retrospectiveRepository.deleteAll()
        studentRepository.deleteAll()
        resetMeasurements()
    }

    @Test
    fun `회원 전체 materialize와 DB 페이징의 차이를 같은 시나리오로 기록한다`() {
        val unfiltered = measure(
            scenario = "unfiltered",
            pageable = firstPage(),
            search = null
        )
        assertSuccessfulPage(
            snapshot = unfiltered,
            expectedPageSize = PAGE_SIZE,
            expectedTotalElements = FIXTURE_STUDENT_COUNT.toLong()
        )
        assertThat(unfiltered.studentEntitiesMaterialized).isEqualTo(PAGE_SIZE)
        assertThat(unfiltered.commandCounts).isEqualTo(
            Phase1cMongoCommandCounts(
                studentFind = 1,
                studentAggregate = 1,
                studentCount = 0,
                studentGetMore = 0,
                retrospectiveAggregate = 1,
                retrospectiveGetMore = 0,
                totalRead = 3
            )
        )
        val studentPageCommand = unfiltered.studentReadCommands.single { it.command == "find" }
        assertThat(studentPageCommand.filterJson).isEqualTo("{}")
        assertThat(studentPageCommand.sortJson).isEqualTo("""{"rating": -1, "_id": 1}""")
        assertThat(studentPageCommand.skip).isNull()
        assertThat(studentPageCommand.limit).isEqualTo(PAGE_SIZE.toLong())
        assertStudentQueryPlans(unfiltered, expectedPageOffset = 0)

        val deepPage = measure(
            scenario = "deep-page",
            pageable = PageRequest.of(
                DEEP_PAGE,
                PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "rating")
            ),
            search = null
        )
        assertSuccessfulPage(
            snapshot = deepPage,
            expectedPageSize = PAGE_SIZE,
            expectedTotalElements = FIXTURE_STUDENT_COUNT.toLong()
        )
        assertThat(deepPage.outcome.firstStudentId).isEqualTo("phase1c-student-500")
        assertThat(deepPage.outcome.lastStudentId).isEqualTo("phase1c-student-481")
        assertThat(deepPage.studentEntitiesMaterialized).isEqualTo(PAGE_SIZE)
        assertThat(deepPage.commandCounts).isEqualTo(pageCommandCounts())
        val deepPageCommand = deepPage.studentReadCommands.single { it.command == "find" }
        assertThat(deepPageCommand.skip).isEqualTo(DEEP_PAGE_OFFSET)
        assertThat(deepPageCommand.limit).isEqualTo(PAGE_SIZE.toLong())
        assertStudentQueryPlans(deepPage, expectedPageOffset = DEEP_PAGE_OFFSET)

        val searchEmpty = measure(
            scenario = "search-empty",
            pageable = firstPage(),
            search = NO_MATCH_SEARCH
        )
        assertSuccessfulPage(
            snapshot = searchEmpty,
            expectedPageSize = 0,
            expectedTotalElements = 0
        )
        assertThat(searchEmpty.studentEntitiesMaterialized).isZero()
        assertThat(searchEmpty.commandCounts).isEqualTo(countOnlyCommandCounts())

        val outOfRange = measure(
            scenario = "out-of-range",
            pageable = PageRequest.of(
                OUT_OF_RANGE_PAGE,
                PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "rating")
            ),
            search = null
        )
        assertOutOfRangeOutcome(outOfRange)
        assertThat(outOfRange.studentEntitiesMaterialized).isZero()
        assertThat(outOfRange.commandCounts).isEqualTo(countOnlyCommandCounts())
    }

    private fun measure(
        scenario: String,
        pageable: Pageable,
        search: String?
    ): Phase1cAdminQuerySnapshot {
        resetMeasurements()

        val result = runCatching {
            adminService.getAllUsers(
                pageable = pageable,
                search = search
            )
        }
        val snapshot = Phase1cAdminQuerySnapshot(
            schemaVersion = SCHEMA_VERSION,
            source = Phase1cBaselineSource(
                commitSha = System.getenv("ADMIN_QUERY_BASELINE_COMMIT_SHA") ?: "NOT_CAPTURED",
                gitDirty = System.getenv("ADMIN_QUERY_BASELINE_GIT_DIRTY")?.toBooleanStrictOrNull()
            ),
            database = mongoTemplate.db.name,
            scenario = scenario,
            fixtureStudentCount = FIXTURE_STUDENT_COUNT,
            request = Phase1cAdminQueryRequest(
                pageNumber = pageable.pageNumber,
                pageSize = pageable.pageSize,
                sort = pageable.sort.map { order ->
                    Phase1cSortOrder(
                        property = order.property,
                        direction = order.direction.name
                    )
                }.toList(),
                search = search
            ),
            outcome = result.fold(
                onSuccess = ::successfulOutcome,
                onFailure = ::failedOutcome
            ),
            studentEntitiesMaterialized = materializationCounter.count(),
            commandCounts = commandCounts(),
            studentReadCommands = studentReadCommands(),
            studentIndexes = queryPlanExplainer.collectIndexes(Student::class.java),
            studentPage = commandCounter.studentReadCommands()
                .singleOrNull { command -> command.command == "find" }
                ?.let { command -> queryPlanExplainer.explainFind("$scenario-page", command) },
            studentCount = queryPlanExplainer.explainAggregation(
                collection = STUDENT_COLLECTION,
                query = "$scenario-count",
                pipeline = commandCounter.requireSingleAggregatePipeline(STUDENT_COLLECTION)
            )
        )

        assertThat(snapshot.studentReadCommands)
            .describedAs("$scenario 시나리오의 students read command")
            .isNotEmpty()
        writeSnapshotIfRequested(snapshot)
        return snapshot
    }

    private fun assertSuccessfulPage(
        snapshot: Phase1cAdminQuerySnapshot,
        expectedPageSize: Int,
        expectedTotalElements: Long
    ) {
        assertThat(snapshot.outcome.status).isEqualTo(Phase1cOutcomeStatus.SUCCESS)
        assertThat(snapshot.outcome.resultPageSize).isEqualTo(expectedPageSize)
        assertThat(snapshot.outcome.totalElements).isEqualTo(expectedTotalElements)
        assertThat(snapshot.outcome.exceptionType).isNull()
    }

    private fun assertOutOfRangeOutcome(snapshot: Phase1cAdminQuerySnapshot) {
        assertThat(snapshot.outcome.status).isEqualTo(Phase1cOutcomeStatus.SUCCESS)
        assertThat(snapshot.outcome.resultPageSize).isZero()
        assertThat(snapshot.outcome.totalElements)
            .isEqualTo(FIXTURE_STUDENT_COUNT.toLong())
        assertThat(snapshot.outcome.exceptionType).isNull()
    }

    private fun countOnlyCommandCounts(): Phase1cMongoCommandCounts {
        return Phase1cMongoCommandCounts(
            studentFind = 0,
            studentAggregate = 1,
            studentCount = 0,
            studentGetMore = 0,
            retrospectiveAggregate = 0,
            retrospectiveGetMore = 0,
            totalRead = 1
        )
    }

    private fun pageCommandCounts(): Phase1cMongoCommandCounts {
        return Phase1cMongoCommandCounts(
            studentFind = 1,
            studentAggregate = 1,
            studentCount = 0,
            studentGetMore = 0,
            retrospectiveAggregate = 1,
            retrospectiveGetMore = 0,
            totalRead = 3
        )
    }

    private fun assertStudentQueryPlans(
        snapshot: Phase1cAdminQuerySnapshot,
        expectedPageOffset: Long
    ) {
        assertThat(snapshot.studentIndexes.map { it.name }).contains("_id_")

        val pagePlan = requireNotNull(snapshot.studentPage)
        assertThat(pagePlan.winningPlanStage).isIn("COLLSCAN", "IXSCAN")
        assertThat(pagePlan.nReturned).isEqualTo(PAGE_SIZE.toLong())
        assertThat(pagePlan.totalDocsExamined)
            .isBetween(PAGE_SIZE.toLong(), FIXTURE_STUDENT_COUNT.toLong())
        assertThat(pagePlan.totalKeysExamined)
            .isBetween(0L, expectedPageOffset + PAGE_SIZE)

        assertThat(snapshot.studentCount.winningPlanStage).isIn("COLLSCAN", "IXSCAN")
        assertThat(snapshot.studentCount.nReturned).isEqualTo(1)
        assertThat(snapshot.studentCount.totalDocsExamined)
            .isBetween(0L, FIXTURE_STUDENT_COUNT.toLong())
    }

    private fun successfulOutcome(result: Page<*>): Phase1cAdminQueryOutcome {
        val content = result.content
        val firstStudent = content.firstOrNull() as? AdminUserResponse
        val lastStudent = content.lastOrNull() as? AdminUserResponse
        return Phase1cAdminQueryOutcome(
            status = Phase1cOutcomeStatus.SUCCESS,
            resultPageSize = content.size,
            totalElements = result.totalElements,
            firstStudentId = firstStudent?.id,
            lastStudentId = lastStudent?.id,
            exceptionType = null
        )
    }

    private fun failedOutcome(error: Throwable): Phase1cAdminQueryOutcome {
        return Phase1cAdminQueryOutcome(
            status = Phase1cOutcomeStatus.EXCEPTION,
            resultPageSize = null,
            totalElements = null,
            firstStudentId = null,
            lastStudentId = null,
            exceptionType = error::class.qualifiedName
        )
    }

    private fun writeSnapshotIfRequested(snapshot: Phase1cAdminQuerySnapshot) {
        val configuredOutputDirectory = System.getenv(OUTPUT_DIRECTORY_ENV)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val outputDirectory = Path.of(configuredOutputDirectory).toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)
        val outputFile = outputDirectory.resolve(
            "admin-users-db-pagination-${snapshot.scenario}.json"
        )

        jacksonObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValue(outputFile.toFile(), snapshot)
    }

    private fun resetMeasurements() {
        commandCounter.reset()
        materializationCounter.reset()
    }

    private fun commandCounts(): Phase1cMongoCommandCounts {
        return Phase1cMongoCommandCounts(
            studentFind = commandCounter.countCommands("find", STUDENT_COLLECTION),
            studentAggregate = commandCounter.countCommands("aggregate", STUDENT_COLLECTION),
            studentCount = commandCounter.countCommands("count", STUDENT_COLLECTION),
            studentGetMore = commandCounter.countCommands("getMore", STUDENT_COLLECTION),
            retrospectiveAggregate = commandCounter.countCommands("aggregate", RETROSPECTIVE_COLLECTION),
            retrospectiveGetMore = commandCounter.countCommands("getMore", RETROSPECTIVE_COLLECTION),
            totalRead = commandCounter.countReadCommands()
        )
    }

    private fun studentReadCommands(): List<Phase1cObservedMongoReadCommand> {
        return commandCounter.studentReadCommands().map { observed ->
            Phase1cObservedMongoReadCommand(
                command = observed.command,
                collection = observed.collection,
                filterJson = observed.filterJson,
                sortJson = observed.sortJson,
                pipelineJson = observed.pipelineJson,
                skip = observed.skip,
                limit = observed.limit,
                batchSize = observed.batchSize
            )
        }
    }

    private fun firstPage(): Pageable {
        return PageRequest.of(
            0,
            PAGE_SIZE,
            Sort.by(Sort.Direction.DESC, "rating")
        )
    }

    private fun createStudent(index: Int): Student {
        return Student(
            id = "phase1c-student-$index",
            nickname = Nickname("phase1c$index"),
            provider = Provider.BOJ,
            providerId = "phase1c-provider-$index",
            email = "phase1c-$index@example.com",
            bojId = BojId("phase1c_$index"),
            password = "encoded-password",
            rating = 1_000 + index,
            currentTier = Tier.GOLD,
            role = Role.USER,
            createdAt = FIXTURE_CREATED_AT.plusSeconds(index.toLong())
        )
    }

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val FIXTURE_STUDENT_COUNT = 1_000
        private const val PAGE_SIZE = 20
        private const val DEEP_PAGE = 25
        private const val DEEP_PAGE_OFFSET = 500L
        private const val OUT_OF_RANGE_PAGE = 51
        private const val NO_MATCH_SEARCH = "no-match-phase1c"
        private const val OUTPUT_DIRECTORY_ENV = "ADMIN_QUERY_BASELINE_OUTPUT_DIR"
        private const val STUDENT_COLLECTION = "students"
        private const val RETROSPECTIVE_COLLECTION = "retrospectives"
        private val FIXTURE_CREATED_AT: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class Phase1cAdminQueryMeasurementConfiguration {

    @Bean
    fun studentMaterializationCounter(): StudentMaterializationCounter {
        return StudentMaterializationCounter()
    }
}

class StudentMaterializationCounter : AfterConvertCallback<Student> {

    private val count = AtomicInteger()

    override fun onAfterConvert(
        entity: Student,
        document: org.bson.Document,
        collection: String
    ): Student {
        count.incrementAndGet()
        return entity
    }

    fun reset() {
        count.set(0)
    }

    fun count(): Int = count.get()
}

data class Phase1cAdminQuerySnapshot(
    val schemaVersion: Int,
    val source: Phase1cBaselineSource,
    val database: String,
    val scenario: String,
    val fixtureStudentCount: Int,
    val request: Phase1cAdminQueryRequest,
    val outcome: Phase1cAdminQueryOutcome,
    val studentEntitiesMaterialized: Int,
    val commandCounts: Phase1cMongoCommandCounts,
    val studentReadCommands: List<Phase1cObservedMongoReadCommand>,
    val studentIndexes: List<MongoIndexBaseline>,
    val studentPage: MongoQueryExecutionBaseline?,
    val studentCount: MongoQueryExecutionBaseline
)

data class Phase1cBaselineSource(
    val commitSha: String,
    val gitDirty: Boolean?
)

data class Phase1cAdminQueryRequest(
    val pageNumber: Int,
    val pageSize: Int,
    val sort: List<Phase1cSortOrder>,
    val search: String?
)

data class Phase1cSortOrder(
    val property: String,
    val direction: String
)

data class Phase1cAdminQueryOutcome(
    val status: Phase1cOutcomeStatus,
    val resultPageSize: Int?,
    val totalElements: Long?,
    val firstStudentId: String?,
    val lastStudentId: String?,
    val exceptionType: String?
)

enum class Phase1cOutcomeStatus {
    SUCCESS,
    EXCEPTION
}

data class Phase1cMongoCommandCounts(
    val studentFind: Int,
    val studentAggregate: Int,
    val studentCount: Int,
    val studentGetMore: Int,
    val retrospectiveAggregate: Int,
    val retrospectiveGetMore: Int,
    val totalRead: Int
)

data class Phase1cObservedMongoReadCommand(
    val command: String,
    val collection: String,
    val filterJson: String?,
    val sortJson: String?,
    val pipelineJson: String?,
    val skip: Long?,
    val limit: Long?,
    val batchSize: Long?
)
