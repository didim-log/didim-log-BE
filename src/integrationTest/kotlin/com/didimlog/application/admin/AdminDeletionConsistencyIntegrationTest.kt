package com.didimlog.application.admin

import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.student.AccountDeletionService
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
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
import io.mockk.every
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
@DisplayName("관리자 강제 탈퇴 정합성 통합 테스트")
class AdminDeletionConsistencyIntegrationTest {

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
    @DisplayName("관리자 강제 탈퇴는 대상 회원 데이터만 모두 삭제한다")
    fun `admin hard delete removes only target student data`() {
        val saved = studentRepository.save(
            Student(
                id = "admin-delete-student",
                nickname = Nickname("deleteuser"),
                provider = Provider.BOJ,
                providerId = "delete-provider",
                bojId = BojId("deleteboj"),
                password = "encoded-password",
                currentTier = Tier.BRONZE,
                role = Role.USER
            )
        )
        val studentId = requireNotNull(saved.id)
        val otherStudent = studentRepository.save(
            Student(
                id = "preserved-student",
                nickname = Nickname("preserved"),
                provider = Provider.BOJ,
                providerId = "preserved-provider",
                bojId = BojId("preservedboj"),
                password = "encoded-password",
                currentTier = Tier.SILVER,
                role = Role.USER
            )
        )
        val otherStudentId = requireNotNull(otherStudent.id)

        val targetRetrospective = retrospectiveRepository.save(
            Retrospective(
                studentId = studentId,
                problemId = "1000",
                content = "강제 탈퇴 시 삭제할 대상 회원 회고입니다."
            )
        )
        val otherRetrospective = retrospectiveRepository.save(
            Retrospective(
                studentId = otherStudentId,
                problemId = "1000",
                content = "강제 탈퇴 뒤에도 보존할 다른 회원 회고입니다."
            )
        )
        val targetFeedback = feedbackRepository.save(
            Feedback(
                writerId = studentId,
                content = "강제 탈퇴 시 삭제할 대상 회원 피드백입니다.",
                type = FeedbackType.SUGGESTION
            )
        )
        val otherFeedback = feedbackRepository.save(
            Feedback(
                writerId = otherStudentId,
                content = "강제 탈퇴 뒤에도 보존할 다른 회원 피드백입니다.",
                type = FeedbackType.BUG
            )
        )
        val targetOwnedLog = logRepository.save(
            createLog(studentId = studentId, bojId = "deleteboj", title = "대상 회원 로그")
        )
        val sameBojLegacyLog = logRepository.save(
            createLog(studentId = null, bojId = "deleteboj", title = "소유자를 확인할 수 없는 이전 로그")
        )
        val otherOwnedLog = logRepository.save(
            createLog(studentId = otherStudentId, bojId = "deleteboj", title = "다른 회원 소유 로그")
        )
        val otherLegacyLog = logRepository.save(
            createLog(studentId = null, bojId = "preservedboj", title = "다른 회원 이전 로그")
        )
        val targetCustomTemplate = templateRepository.save(
            createTemplate(studentId = studentId, title = "대상 회원 템플릿")
        )
        val otherCustomTemplate = templateRepository.save(
            createTemplate(studentId = otherStudentId, title = "다른 회원 템플릿")
        )
        val systemTemplate = templateRepository.save(
            Template(
                title = "시스템 템플릿",
                content = "모든 회원이 사용하는 시스템 템플릿입니다.",
                type = TemplateOwnershipType.SYSTEM
            )
        )
        val targetPasswordResetCode = passwordResetCodeRepository.save(
            createPasswordResetCode(
                studentId = studentId,
                resetCode = "target-reset-code",
                bojId = "deleteboj"
            )
        )
        val otherPasswordResetCode = passwordResetCodeRepository.save(
            createPasswordResetCode(
                studentId = otherStudentId,
                resetCode = "other-reset-code",
                bojId = "preservedboj"
            )
        )

        val refreshTokenService = mockk<RefreshTokenService>()
        every { refreshTokenService.revokeAllForStudent(studentId) } answers {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(studentId)),
                Update().inc("documentVersion", 1),
                Student::class.java
            )
            Unit
        }
        val coordinator = ImmediateCredentialSessionCoordinator()
        val accountDeletionService = AccountDeletionService(
            studentRepository = studentRepository,
            retrospectiveRepository = retrospectiveRepository,
            feedbackRepository = feedbackRepository,
            logRepository = logRepository,
            templateRepository = templateRepository,
            passwordResetCodeRepository = passwordResetCodeRepository,
            refreshTokenService = refreshTokenService,
            studentLifecycleCoordinator = coordinator
        )
        val adminService = AdminService(
            studentRepository = studentRepository,
            quoteRepository = mockk<QuoteRepository>(relaxed = true),
            retrospectiveRepository = retrospectiveRepository,
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            refreshTokenService = refreshTokenService,
            credentialSessionCoordinator = coordinator,
            accountDeletionService = accountDeletionService
        )

        adminService.deleteUser(studentId)

        assertThat(studentRepository.existsById(studentId)).isFalse()
        assertThat(retrospectiveRepository.existsById(requireNotNull(targetRetrospective.id))).isFalse()
        assertThat(feedbackRepository.existsById(requireNotNull(targetFeedback.id))).isFalse()
        assertThat(logRepository.existsById(requireNotNull(targetOwnedLog.id))).isFalse()
        assertThat(templateRepository.existsById(requireNotNull(targetCustomTemplate.id))).isFalse()
        assertThat(passwordResetCodeRepository.existsById(requireNotNull(targetPasswordResetCode.id))).isFalse()

        assertThat(studentRepository.existsById(otherStudentId)).isTrue()
        assertThat(retrospectiveRepository.existsById(requireNotNull(otherRetrospective.id))).isTrue()
        assertThat(feedbackRepository.existsById(requireNotNull(otherFeedback.id))).isTrue()
        assertThat(logRepository.existsById(requireNotNull(sameBojLegacyLog.id))).isTrue()
        assertThat(logRepository.existsById(requireNotNull(otherOwnedLog.id))).isTrue()
        assertThat(logRepository.existsById(requireNotNull(otherLegacyLog.id))).isTrue()
        assertThat(templateRepository.existsById(requireNotNull(otherCustomTemplate.id))).isTrue()
        assertThat(templateRepository.existsById(requireNotNull(systemTemplate.id))).isTrue()
        assertThat(passwordResetCodeRepository.existsById(requireNotNull(otherPasswordResetCode.id))).isTrue()
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
    }

    private fun createLog(studentId: String?, bojId: String, title: String): Log {
        return Log(
            title = LogTitle(title),
            content = LogContent("$title 본문입니다."),
            code = LogCode("fun main() = println(\"$title\")"),
            studentId = studentId,
            bojId = BojId(bojId)
        )
    }

    private fun createTemplate(studentId: String, title: String): Template {
        return Template(
            studentId = studentId,
            title = title,
            content = "$title 본문입니다.",
            type = TemplateOwnershipType.CUSTOM
        )
    }

    private fun createPasswordResetCode(
        studentId: String,
        resetCode: String,
        bojId: String
    ): PasswordResetCode {
        return PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = 0,
            bojId = bojId,
            expiresAt = LocalDateTime.now().plusDays(1)
        )
    }

    companion object {
        private val testDatabaseName =
            "didimlog-admin-deletion-${UUID.randomUUID().toString().replace("-", "")}"

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
