package com.didimlog.application.student

import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Feedback
import com.didimlog.domain.Log
import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.infra.solvedac.SolvedAcClient
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DataMongoTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("회원 탈퇴 정합성 통합 테스트")
class StudentWithdrawalConsistencyIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var logRepository: LogRepository

    @Autowired
    private lateinit var templateRepository: TemplateRepository

    @Autowired
    private lateinit var passwordResetCodeRepository: PasswordResetCodeRepository

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    @DisplayName("본인 탈퇴는 학생과 모든 사용자 소유 데이터를 제거하고 공용 데이터는 보존한다")
    fun `self withdrawal removes all account data`() {
        val student = studentRepository.save(
            Student(
                id = "withdraw-student",
                nickname = Nickname("withdraw1"),
                provider = Provider.BOJ,
                providerId = "withdraw-provider",
                bojId = BojId("withdrawboj"),
                password = "encoded-password",
                currentTier = Tier.BRONZE,
                role = Role.USER
            )
        )
        val studentId = requireNotNull(student.id)
        retrospectiveRepository.save(
            Retrospective(
                studentId = studentId,
                problemId = "1000",
                content = "탈퇴 시 함께 삭제될 회고입니다."
            )
        )
        feedbackRepository.save(
            Feedback(
                writerId = studentId,
                content = "탈퇴 시 함께 삭제될 피드백입니다.",
                type = FeedbackType.SUGGESTION
            )
        )
        val ownedLog = logRepository.save(
            Log(
                title = LogTitle("사용자 로그"),
                content = LogContent("탈퇴 시 삭제할 로그"),
                code = LogCode("println(1)"),
                studentId = studentId,
                bojId = BojId("withdrawboj")
            )
        )
        val legacyLog = logRepository.save(
            Log(
                title = LogTitle("레거시 로그"),
                content = LogContent("학생 ID 도입 전 로그"),
                code = LogCode("println(2)"),
                bojId = BojId("withdrawboj")
            )
        )
        val unrelatedLog = logRepository.save(
            Log(
                title = LogTitle("다른 사용자 로그"),
                content = LogContent("탈퇴 대상이 아닌 로그"),
                code = LogCode("println(3)"),
                studentId = "other-student",
                bojId = BojId("otherboj")
            )
        )
        val customTemplate = templateRepository.save(
            Template(
                studentId = studentId,
                title = "사용자 템플릿",
                content = "사용자 템플릿 본문",
                type = TemplateOwnershipType.CUSTOM
            )
        )
        val systemTemplate = templateRepository.save(
            Template(
                title = "시스템 템플릿",
                content = "시스템 템플릿 본문",
                type = TemplateOwnershipType.SYSTEM
            )
        )
        val resetCode = passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = "withdraw-reset-code",
                studentId = studentId,
                expiresAt = LocalDateTime.now().plusMinutes(10)
            )
        )
        val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
        val coordinator = object : CredentialSessionCoordinator {
            override fun <T> execute(studentId: String, action: () -> T): T {
                mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").`is`(studentId)),
                    Update().inc("documentVersion", 1),
                    Student::class.java
                )
                return action()
            }

            override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
                return execute(studentId, action)
            }
        }
        val accountDeletionService = AccountDeletionService(
            studentRepository = studentRepository,
            retrospectiveRepository = retrospectiveRepository,
            feedbackRepository = feedbackRepository,
            logRepository = logRepository,
            templateRepository = templateRepository,
            passwordResetCodeRepository = passwordResetCodeRepository,
            refreshTokenService = refreshTokenService,
            credentialSessionCoordinator = coordinator
        )
        val studentService = StudentService(
            studentRepository = studentRepository,
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            solvedAcClient = mockk<SolvedAcClient>(relaxed = true),
            refreshTokenService = refreshTokenService,
            credentialSessionCoordinator = coordinator,
            accountDeletionService = accountDeletionService
        )

        studentService.withdraw(studentId)

        assertThat(studentRepository.existsById(studentId)).isFalse()
        assertThat(retrospectiveRepository.findAllByStudentId(studentId)).isEmpty()
        assertThat(feedbackRepository.findAll()).isEmpty()
        assertThat(logRepository.existsById(requireNotNull(ownedLog.id))).isFalse()
        assertThat(logRepository.existsById(requireNotNull(legacyLog.id))).isTrue()
        assertThat(logRepository.existsById(requireNotNull(unrelatedLog.id))).isTrue()
        assertThat(templateRepository.existsById(requireNotNull(customTemplate.id))).isFalse()
        assertThat(templateRepository.existsById(requireNotNull(systemTemplate.id))).isTrue()
        assertThat(passwordResetCodeRepository.existsById(requireNotNull(resetCode.id))).isFalse()
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
    }

    companion object {
        private val testDatabaseName =
            "didimlog-withdrawal-${UUID.randomUUID().toString().replace("-", "")}"

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
