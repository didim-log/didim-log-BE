package com.didimlog.application.student

import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Feedback
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.infra.solvedac.SolvedAcClient
import io.mockk.mockk
import io.mockk.verify
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

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    @DisplayName("잠금 진입 시 문서 버전이 바뀌어도 ID 삭제로 학생과 연관 데이터를 제거한다")
    fun `hard delete uses stable student id`() {
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
        val studentService = StudentService(
            studentRepository = studentRepository,
            retrospectiveRepository = retrospectiveRepository,
            feedbackRepository = feedbackRepository,
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            solvedAcClient = mockk<SolvedAcClient>(relaxed = true),
            refreshTokenService = refreshTokenService,
            credentialSessionCoordinator = coordinator
        )

        studentService.withdraw(studentId)

        assertThat(studentRepository.existsById(studentId)).isFalse()
        assertThat(retrospectiveRepository.findAllByStudentId(studentId)).isEmpty()
        assertThat(feedbackRepository.findAll()).isEmpty()
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
