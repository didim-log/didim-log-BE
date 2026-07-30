package com.didimlog.application.admin

import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import io.mockk.every
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
@DisplayName("관리자 강제 탈퇴 정합성 통합 테스트")
class AdminDeletionConsistencyIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    @DisplayName("세션 정리 중 문서 버전이 바뀌어도 ID 삭제로 강제 탈퇴를 완료한다")
    fun `admin hard delete wins over document version change`() {
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
        val refreshTokenService = mockk<RefreshTokenService>()
        every { refreshTokenService.revokeAllForStudent(studentId) } answers {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(studentId)),
                Update().inc("documentVersion", 1),
                Student::class.java
            )
            Unit
        }
        val adminService = AdminService(
            studentRepository = studentRepository,
            quoteRepository = mockk<QuoteRepository>(relaxed = true),
            retrospectiveRepository = retrospectiveRepository,
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            refreshTokenService = refreshTokenService,
            credentialSessionCoordinator = ImmediateCredentialSessionCoordinator()
        )

        adminService.deleteUser(studentId)

        assertThat(studentRepository.existsById(studentId)).isFalse()
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
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
