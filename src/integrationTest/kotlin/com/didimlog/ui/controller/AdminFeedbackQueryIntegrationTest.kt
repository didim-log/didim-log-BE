package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.application.admin.AdminService
import com.didimlog.application.admin.query.MongoQueryPlanExplainer
import com.didimlog.application.admin.query.ObservedMongoReadCommand
import com.didimlog.application.feedback.FeedbackService
import com.didimlog.application.notice.NoticeService
import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.domain.Feedback
import com.didimlog.domain.Student
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.config.MongoConfig
import com.didimlog.ui.dto.FeedbackResponse
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import io.mockk.mockk
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles

@DataMongoTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("관리자 피드백 목록 Mongo 조회 통합 테스트")
@Import(MongoConfig::class, AdminFeedbackQueryMongoConfiguration::class)
class AdminFeedbackQueryIntegrationTest {

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandRecorder: AdminFeedbackQueryMongoCommandRecorder

    private lateinit var feedbackService: FeedbackService
    private lateinit var controller: AdminController

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        seedFixture()
        feedbackService = FeedbackService(
            feedbackRepository = feedbackRepository,
            studentRepository = studentRepository,
            studentLifecycleCoordinator = mockk<StudentLifecycleCoordinator>(relaxed = true)
        )
        controller = AdminController(
            adminService = mockk<AdminService>(relaxed = true),
            feedbackService = feedbackService,
            adminAuditService = mockk<AdminAuditService>(relaxed = true),
            studentRepository = studentRepository,
            noticeService = mockk<NoticeService>(relaxed = true)
        )
        commandRecorder.disableAndReset()
    }

    @AfterEach
    fun tearDown() {
        commandRecorder.disableAndReset()
        mongoTemplate.db.drop()
    }

    @ParameterizedTest(name = "page size {0}")
    @ValueSource(ints = [1, 5, 20])
    fun `작성자 BOJ ID를 한 번에 조회하고 기존 응답을 유지한다`(pageSize: Int) {
        val legacy = capture {
            legacyFeedbackPage(pageSize)
        }
        val current = capture {
            requireNotNull(controller.getAllFeedbacks(page = 1, size = pageSize).body)
        }

        assertThat(canonical(current.value)).isEqualTo(canonical(legacy.value))
        assertThat(sha256(canonical(current.value))).isEqualTo(sha256(canonical(legacy.value)))
        assertThat(sha256(canonical(current.value)))
            .isEqualTo(EXPECTED_PAGE_HASHES.getValue(pageSize))
        assertThat(current.value.content).hasSize(pageSize)
        assertThat(current.value.totalElements).isEqualTo(TOTAL_FEEDBACK_COUNT.toLong())
        assertThat(current.value.number).isZero()
        assertThat(current.value.size).isEqualTo(pageSize)

        assertPageReadCommands(legacy.commands, expectedStudentFinds = pageSize)
        assertPageReadCommands(current.commands, expectedStudentFinds = 1)
        assertThat(legacy.commands.totalReadCount()).isEqualTo(pageSize + PAGE_READ_COMMANDS)
        assertThat(current.commands.totalReadCount()).isEqualTo(PAGE_READ_COMMANDS + 1)

        val studentFind = current.commands.requireSingle("find", STUDENT_COLLECTION)
        val requestedWriterIds = requireNotNull(studentFind.filter)
            .getDocument("_id")
            .getArray("\$in")
            .values
            .map { value -> value.asString().value }
        assertThat(requestedWriterIds)
            .containsExactlyInAnyOrderElementsOf(
                current.value.content.map(FeedbackResponse::writerId)
            )

        val projection = requireNotNull(studentFind.projection)
        assertThat(projection.keys).containsExactlyInAnyOrder("_id", "bojId")
        assertThat(projection.getNumber("_id").intValue()).isEqualTo(1)
        assertThat(projection.getNumber("bojId").intValue()).isEqualTo(1)

        if (pageSize == PAGE_SIZE) {
            assertFullPageContract(current.value)
            assertStudentQueryPlan(studentFind)
        }
    }

    @Test
    fun `빈 페이지에서는 작성자 조회를 생략한다`() {
        val result = capture {
            requireNotNull(controller.getAllFeedbacks(page = 3, size = PAGE_SIZE).body)
        }

        assertThat(result.value.content).isEmpty()
        assertThat(result.value.totalElements).isEqualTo(TOTAL_FEEDBACK_COUNT.toLong())
        assertThat(result.commands.collectionReadCount(STUDENT_COLLECTION)).isZero()
    }

    private fun legacyFeedbackPage(pageSize: Int): Page<FeedbackResponse> {
        val pageable = PageRequest.of(
            0,
            pageSize,
            Sort.by(Sort.Direction.DESC, "createdAt")
        )
        return feedbackService.getAllFeedbacks(pageable).map { feedback ->
            val student = studentRepository.findById(feedback.writerId).orElse(null)
            FeedbackResponse(
                id = feedback.id ?: "",
                writerId = feedback.writerId,
                bojId = student?.bojId?.value,
                content = feedback.content,
                type = feedback.type.value,
                status = feedback.status.value,
                createdAt = feedback.createdAt,
                updatedAt = feedback.updatedAt
            )
        }
    }

    private fun capture(action: () -> Page<FeedbackResponse>): CapturedFeedbackPage {
        commandRecorder.enableAndReset()
        val value = action()
        return CapturedFeedbackPage(
            value = value,
            commands = commandRecorder.disableAndSnapshot()
        )
    }

    private fun assertPageReadCommands(
        commands: AdminFeedbackQueryMongoCommandSnapshot,
        expectedStudentFinds: Int
    ) {
        assertThat(commands.count("find", FEEDBACK_COLLECTION)).isEqualTo(1)
        assertThat(commands.count("aggregate", FEEDBACK_COLLECTION)).isEqualTo(1)
        assertThat(commands.count("count", FEEDBACK_COLLECTION)).isZero()
        assertThat(commands.count("find", STUDENT_COLLECTION)).isEqualTo(expectedStudentFinds)
        assertThat(commands.count("aggregate", STUDENT_COLLECTION)).isZero()
        assertThat(commands.count("count", STUDENT_COLLECTION)).isZero()
        assertThat(commands.count("getMore", FEEDBACK_COLLECTION)).isZero()
        assertThat(commands.count("getMore", STUDENT_COLLECTION)).isZero()
    }

    private fun assertFullPageContract(result: Page<FeedbackResponse>) {
        assertThat(result.content.map(FeedbackResponse::id))
            .containsExactly(*(20 downTo 1).map { index -> "feedback-$index" }.toTypedArray())
        assertThat(result.content.associateBy { it.writerId }.getValue(MISSING_STUDENT_ID).bojId)
            .isNull()
        assertThat(result.content.associateBy { it.writerId }.getValue(NULL_BOJ_ID_STUDENT_ID).bojId)
            .isNull()
        assertThat(result.content.associateBy { it.writerId }.getValue("feedback-student-1").bojId)
            .isEqualTo("boj_user_1")
        assertThat(result.totalPages).isEqualTo(2)
    }

    private fun assertStudentQueryPlan(command: AdminFeedbackQueryMongoCommand) {
        val queryPlan = MongoQueryPlanExplainer(mongoTemplate).explainFind(
            query = "adminFeedbackWriterBojIds",
            command = ObservedMongoReadCommand(
                command = command.name,
                collection = command.collection,
                filter = command.filter?.let { filter -> Document.parse(filter.toJson()) },
                filterJson = command.filter?.toJson(),
                projection = command.projection?.let { projection ->
                    Document.parse(projection.toJson())
                },
                projectionJson = command.projection?.toJson(),
                sort = null,
                sortJson = null,
                skip = null,
                limit = null,
                batchSize = null,
                pipelineJson = null,
                pipeline = null
            )
        )

        assertThat(queryPlan.winningPlanStage).isEqualTo("IXSCAN")
        assertThat(queryPlan.selectedIndexName).isEqualTo("_id_")
        assertThat(queryPlan.selectedIndexKeyPattern)
            .containsExactlyEntriesOf(mapOf("_id" to 1))
        assertThat(queryPlan.hasBlockingSort).isFalse()
        assertThat(queryPlan.nReturned).isEqualTo((PAGE_SIZE - 1).toLong())
        assertThat(queryPlan.totalDocsExamined).isEqualTo((PAGE_SIZE - 1).toLong())
    }

    private fun canonical(page: Page<FeedbackResponse>): String {
        val metadata = listOf(
            page.number,
            page.size,
            page.totalElements,
            page.totalPages
        ).joinToString("|")
        val content = page.content.joinToString("\n") { feedback ->
            listOf(
                feedback.id,
                feedback.writerId,
                feedback.bojId,
                feedback.content,
                feedback.type,
                feedback.status,
                feedback.createdAt,
                feedback.updatedAt
            ).joinToString("|")
        }
        return "$metadata\n$content"
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun seedFixture() {
        studentRepository.saveAll(
            (0 until PAGE_SIZE).map { index ->
                Student(
                    id = "feedback-student-$index",
                    nickname = Nickname("fb-user-$index"),
                    provider = Provider.BOJ,
                    providerId = "feedback-provider-$index",
                    bojId = if (index == PAGE_SIZE - 2) null else BojId("boj_user_$index"),
                    currentTier = Tier.BRONZE,
                    role = Role.USER
                )
            }
        )
        val createdAt = LocalDateTime.of(2026, 7, 30, 12, 0)
        feedbackRepository.saveAll(
            (0 until TOTAL_FEEDBACK_COUNT).map { index ->
                Feedback(
                    writerId = if (index == TOTAL_FEEDBACK_COUNT - 1) {
                        MISSING_STUDENT_ID
                    } else {
                        "feedback-student-$index"
                    },
                    content = "관리자 피드백 목록 조회 테스트 내용 $index",
                    type = FeedbackType.BUG,
                    createdAt = createdAt.plusSeconds(index.toLong()),
                    updatedAt = createdAt.plusSeconds(index.toLong())
                ).copy(id = "feedback-$index")
            }
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val TOTAL_FEEDBACK_COUNT = 21
        const val PAGE_READ_COMMANDS = 2
        const val FEEDBACK_COLLECTION = "feedbacks"
        const val STUDENT_COLLECTION = "students"
        const val MISSING_STUDENT_ID = "feedback-student-missing"
        const val NULL_BOJ_ID_STUDENT_ID = "feedback-student-18"
        val EXPECTED_PAGE_HASHES = mapOf(
            1 to "537b25f329a1c8f14bdceb43651b6cb897e14a98aefcb98f7c0598fed33a2358",
            5 to "11ccb280c5c6e8d2e09cd67e5209d74aff6c9bd0c2d3e36030a9430a41cca95a",
            20 to "fcc419cef50804631fd79b1aaf9cf1821a6e5ebdebd41c965c3e2b07c13dc6f1"
        )
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AdminFeedbackQueryMongoConfiguration {

    @Bean
    fun adminFeedbackQueryMongoCommandRecorder(): AdminFeedbackQueryMongoCommandRecorder {
        return AdminFeedbackQueryMongoCommandRecorder()
    }

    @Bean
    fun adminFeedbackQueryMongoCommandCustomizer(
        recorder: AdminFeedbackQueryMongoCommandRecorder
    ): MongoClientSettingsBuilderCustomizer {
        return MongoClientSettingsBuilderCustomizer { builder: MongoClientSettings.Builder ->
            builder.addCommandListener(recorder)
        }
    }
}

class AdminFeedbackQueryMongoCommandRecorder : CommandListener {
    private val commands = ConcurrentLinkedQueue<AdminFeedbackQueryMongoCommand>()

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
            AdminFeedbackQueryMongoCommand(
                name = event.commandName,
                collection = collection,
                filter = (event.command["filter"] as? BsonDocument)?.clone(),
                projection = (event.command["projection"] as? BsonDocument)?.clone()
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

    fun disableAndSnapshot(): AdminFeedbackQueryMongoCommandSnapshot {
        enabled = false
        val snapshot = commands.toList()
        commands.clear()
        return AdminFeedbackQueryMongoCommandSnapshot(snapshot)
    }

    private companion object {
        val TRACKED_COMMANDS = setOf("find", "aggregate", "count", "getMore")
        val TRACKED_COLLECTIONS = setOf("feedbacks", "students")
    }
}

data class AdminFeedbackQueryMongoCommand(
    val name: String,
    val collection: String,
    val filter: BsonDocument?,
    val projection: BsonDocument?
)

data class AdminFeedbackQueryMongoCommandSnapshot(
    val commands: List<AdminFeedbackQueryMongoCommand>
) {
    fun count(name: String, collection: String): Int {
        return commands.count { command ->
            command.name == name && command.collection == collection
        }
    }

    fun totalReadCount(): Int = commands.size

    fun collectionReadCount(collection: String): Int {
        return commands.count { command -> command.collection == collection }
    }

    fun requireSingle(name: String, collection: String): AdminFeedbackQueryMongoCommand {
        val matching = commands.filter { command ->
            command.name == name && command.collection == collection
        }
        check(matching.size == 1) {
            "$collection $name command가 정확히 1개가 아닙니다. commands=$commands"
        }
        return matching.single()
    }
}

data class CapturedFeedbackPage(
    val value: Page<FeedbackResponse>,
    val commands: AdminFeedbackQueryMongoCommandSnapshot
)
