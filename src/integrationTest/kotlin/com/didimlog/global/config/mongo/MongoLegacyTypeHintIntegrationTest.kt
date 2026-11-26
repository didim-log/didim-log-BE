package com.didimlog.global.config.mongo

import com.didimlog.domain.AdminAuditLog
import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.AdminActionType
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.AdminAuditLogRepository
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.feedback.domain.Feedback
import com.didimlog.feedback.domain.FeedbackStatus
import com.didimlog.feedback.domain.FeedbackType
import com.didimlog.feedback.domain.repository.FeedbackRepository
import com.didimlog.global.config.MongoConfig
import com.didimlog.log.domain.Log
import com.didimlog.log.domain.LogCode
import com.didimlog.log.domain.LogContent
import com.didimlog.log.domain.LogTitle
import com.didimlog.log.domain.repository.LogRepository
import com.didimlog.notice.domain.Notice
import com.didimlog.notice.domain.repository.NoticeRepository
import com.didimlog.problem.domain.Example
import com.didimlog.problem.domain.Problem
import com.didimlog.problem.domain.ProblemCategory
import com.didimlog.problem.domain.ProblemId
import com.didimlog.problem.domain.repository.ProblemRepository
import com.didimlog.quote.domain.Quote
import com.didimlog.quote.domain.repository.QuoteRepository
import com.didimlog.retrospective.domain.Retrospective
import com.didimlog.retrospective.domain.repository.RetrospectiveRepository
import com.didimlog.study.domain.ProblemResult
import com.didimlog.study.domain.Solution
import com.didimlog.study.domain.Solutions
import com.didimlog.study.domain.TimeTakenSeconds
import com.didimlog.template.domain.Template
import com.didimlog.template.domain.TemplateOwnershipType
import com.didimlog.template.domain.repository.TemplateRepository
import java.time.LocalDateTime
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 도메인 패키지 재구성 전에 저장된 문서의 `_class` 값(재구성 전 FQCN)을 그대로 넣고
 * repository로 다시 읽을 수 있는지 확인한다.
 *
 * Spring Data MongoDB는 기본 설정에서 최상위 문서에 `_class`로 FQCN을 기록한다.
 * 클래스를 다른 패키지로 옮기면 기존 문서의 `_class`는 더 이상 로드할 수 없는 이름이 되는데,
 * 이때 매퍼가 repository의 선언 타입으로 되돌아가 읽는지를 이 테스트가 고정한다.
 * 아래 문자열은 재구성 전 위치이므로 클래스를 옮겨도 바꾸지 않는다.
 */
@DataMongoTest
@Import(MongoConfig::class)
@DisplayName("MongoDB 옛 _class 타입 힌트 호환성 통합 테스트")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoLegacyTypeHintIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var adminAuditLogRepository: AdminAuditLogRepository

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var logRepository: LogRepository

    @Autowired
    private lateinit var noticeRepository: NoticeRepository

    @Autowired
    private lateinit var passwordResetCodeRepository: PasswordResetCodeRepository

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var quoteRepository: QuoteRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var templateRepository: TemplateRepository

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `옛 _class 값을 가진 관리자 감사 로그 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            AdminAuditLog(
                id = "audit-1",
                adminId = "admin-1",
                action = AdminActionType.NOTICE_CREATE,
                details = "공지 생성",
                ipAddress = "127.0.0.1",
                createdAt = FIXED_TIME
            ),
            "com.didimlog.domain.AdminAuditLog"
        )

        val found = adminAuditLogRepository.findById("audit-1").orElseThrow()

        assertThat(found.adminId).isEqualTo("admin-1")
        assertThat(found.action).isEqualTo(AdminActionType.NOTICE_CREATE)
    }

    @Test
    fun `옛 _class 값을 가진 피드백 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            Feedback(
                id = "feedback-1",
                writerId = "student-1",
                content = "목록 화면에서 버그가 발생합니다",
                type = FeedbackType.BUG,
                status = FeedbackStatus.PENDING,
                createdAt = FIXED_TIME,
                updatedAt = FIXED_TIME
            ),
            "com.didimlog.domain.Feedback"
        )

        val found = feedbackRepository.findById("feedback-1").orElseThrow()

        assertThat(found.writerId).isEqualTo("student-1")
        assertThat(found.type).isEqualTo(FeedbackType.BUG)
    }

    @Test
    fun `옛 _class 값을 가진 풀이 로그 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            Log(
                id = "log-1",
                title = LogTitle("풀이 제목"),
                content = LogContent("풀이 내용"),
                code = LogCode("print(1)"),
                studentId = "student-1",
                createdAt = FIXED_TIME
            ),
            "com.didimlog.domain.Log"
        )

        val found = logRepository.findById("log-1").orElseThrow()

        assertThat(found.title).isEqualTo(LogTitle("풀이 제목"))
        assertThat(found.code).isEqualTo(LogCode("print(1)"))
    }

    @Test
    fun `옛 _class 값을 가진 공지 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            Notice(id = "notice-1", title = "공지", content = "본문", createdAt = FIXED_TIME, updatedAt = FIXED_TIME),
            "com.didimlog.domain.Notice"
        )

        val found = noticeRepository.findById("notice-1").orElseThrow()

        assertThat(found.title).isEqualTo("공지")
    }

    @Test
    fun `옛 _class 값을 가진 비밀번호 재설정 코드 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            PasswordResetCode(
                id = "reset-1",
                resetCode = "hashed-code",
                studentId = "student-1",
                expiresAt = FIXED_TIME.plusMinutes(30),
                createdAt = FIXED_TIME
            ),
            "com.didimlog.domain.PasswordResetCode"
        )

        val found = passwordResetCodeRepository.findById("reset-1").orElseThrow()

        assertThat(found.studentId).isEqualTo("student-1")
        assertThat(found.expiresAt).isEqualTo(FIXED_TIME.plusMinutes(30))
    }

    @Test
    fun `옛 _class 값을 가진 문제 문서와 내장 예제를 읽는다`() {
        val document = insertWithLegacyTypeHint(
            Problem(
                id = ProblemId("1000"),
                title = "A+B",
                category = ProblemCategory.IMPLEMENTATION,
                difficulty = Tier.BRONZE,
                level = 1,
                url = "https://www.acmicpc.net/problem/1000",
                examples = listOf(Example(input = "1 2", output = "3"))
            ),
            "com.didimlog.domain.Problem"
        ) { stored ->
            stored.getList("examples", Document::class.java)
                .forEach { it[TYPE_KEY] = "com.didimlog.domain.Example" }
        }

        val found = problemRepository.findById("1000").orElseThrow()

        assertThat(document.getList("examples", Document::class.java).single()[TYPE_KEY])
            .isEqualTo("com.didimlog.domain.Example")
        assertThat(found.id).isEqualTo(ProblemId("1000"))
        assertThat(found.examples).containsExactly(Example(input = "1 2", output = "3"))
    }

    @Test
    fun `옛 _class 값을 가진 명언 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            Quote(id = "quote-1", content = "꾸준함", author = "작자 미상"),
            "com.didimlog.domain.Quote"
        )

        val found = quoteRepository.findById("quote-1").orElseThrow()

        assertThat(found.content).isEqualTo("꾸준함")
    }

    @Test
    fun `옛 _class 값을 가진 회고 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            Retrospective(
                id = "retro-1",
                studentId = "student-1",
                problemId = "1000",
                content = "구현 문제를 단순하게 풀었다",
                createdAt = FIXED_TIME,
                mainCategory = ProblemCategory.IMPLEMENTATION,
                solutionResult = ProblemResult.SUCCESS
            ),
            "com.didimlog.domain.Retrospective"
        )

        val found = retrospectiveRepository.findById("retro-1").orElseThrow()

        assertThat(found.content).isEqualTo("구현 문제를 단순하게 풀었다")
        assertThat(found.solutionResult).isEqualTo(ProblemResult.SUCCESS)
    }

    @Test
    fun `옛 _class 값을 가진 학생 문서와 내장 풀이 기록을 읽는다`() {
        val solution = Solution(
            problemId = ProblemId("1000"),
            timeTaken = TimeTakenSeconds(60),
            result = ProblemResult.SUCCESS,
            solvedAt = FIXED_TIME
        )
        val document = insertWithLegacyTypeHint(
            Student(
                id = "student-1",
                nickname = Nickname("legacy_user"),
                provider = Provider.BOJ,
                providerId = "legacy_provider",
                bojId = BojId("legacy_boj"),
                rating = 1234,
                currentTier = Tier.SILVER,
                role = Role.USER,
                solutions = Solutions(mutableListOf(solution)),
                createdAt = FIXED_TIME
            ),
            "com.didimlog.domain.Student"
        ) { stored ->
            val solutions = stored.get("solutions", Document::class.java)
            solutions[TYPE_KEY] = "com.didimlog.domain.Solutions"
            solutions.getList("items", Document::class.java)
                .forEach { it[TYPE_KEY] = "com.didimlog.domain.Solution" }
        }

        val found = studentRepository.findById("student-1").orElseThrow()

        assertThat(document.get("solutions", Document::class.java)[TYPE_KEY])
            .isEqualTo("com.didimlog.domain.Solutions")
        assertThat(found.nickname).isEqualTo(Nickname("legacy_user"))
        assertThat(found.bojId).isEqualTo(BojId("legacy_boj"))
        assertThat(found.solutions.getAll()).containsExactly(solution)
    }

    @Test
    fun `옛 _class 값을 가진 템플릿 문서를 읽는다`() {
        insertWithLegacyTypeHint(
            Template(
                id = "template-1",
                title = "기본 템플릿",
                content = "## 접근",
                type = TemplateOwnershipType.SYSTEM,
                createdAt = FIXED_TIME,
                updatedAt = FIXED_TIME
            ),
            "com.didimlog.domain.template.Template"
        )

        val found = templateRepository.findById("template-1").orElseThrow()

        assertThat(found.title).isEqualTo("기본 템플릿")
        assertThat(found.type).isEqualTo(TemplateOwnershipType.SYSTEM)
    }

    /**
     * 현재 매핑 규칙으로 문서를 만든 뒤 `_class`만 재구성 전 FQCN으로 바꿔 원시 문서로 넣는다.
     * 저장된 원시 문서를 다시 읽어 옛 값이 실제로 들어갔는지 확인하고 돌려준다.
     */
    private fun insertWithLegacyTypeHint(
        entity: Any,
        legacyClassName: String,
        customizeEmbedded: (Document) -> Unit = {}
    ): Document {
        val document = Document()
        mongoTemplate.converter.write(entity, document)
        document[TYPE_KEY] = legacyClassName
        customizeEmbedded(document)

        val collection = mongoTemplate.getCollection(mongoTemplate.getCollectionName(entity.javaClass))
        collection.insertOne(document)

        val stored = collection.find(Document("_id", document["_id"])).first()
        assertThat(stored).isNotNull
        assertThat(stored!![TYPE_KEY]).isEqualTo(legacyClassName)
        return stored
    }

    companion object {
        private const val TYPE_KEY = "_class"
        private val FIXED_TIME: LocalDateTime = LocalDateTime.of(2026, 1, 1, 12, 0, 0)
        private val testDatabaseName =
            "didimlog-legacy-type-hint-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                val port = System.getenv("TEST_MONGO_PORT") ?: "27017"
                "mongodb://localhost:$port/$testDatabaseName"
            }
        }
    }
}
