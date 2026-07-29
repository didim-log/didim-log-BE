package com.didimlog.application.auth

import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.config.mongo.MongoIndexInitializer
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DisplayName("비밀번호 재설정 코드 발급 정합성 통합 테스트")
@DataMongoTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PasswordResetIssuanceConsistencyIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var passwordResetCodeRepository: PasswordResetCodeRepository

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        MongoIndexInitializer(mongoTemplate).ensureIndexes()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `같은 학생에게 다시 발급하면 기존 문서를 새 코드로 교체한다`() {
        val first = issue(
            studentId = STUDENT_ID,
            resetCode = "CODE0001",
            createdAt = BASE_TIME
        )
        val second = issue(
            studentId = STUDENT_ID,
            resetCode = "CODE0002",
            createdAt = BASE_TIME.plusMinutes(1)
        )

        assertThat(second.id).isEqualTo(first.id)
        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        val stored = findByStudentId(STUDENT_ID).single()
        assertThat(stored.resetCode).isEqualTo("CODE0002")
        assertThat(stored.expiresAt).isEqualTo(BASE_TIME.plusMinutes(31))
        assertThat(stored.createdAt).isEqualTo(BASE_TIME.plusMinutes(1))
        assertThat(existsByResetCode("CODE0001")).isFalse()
        assertThat(existsByResetCode("CODE0002")).isTrue()
    }

    @Test
    fun `같은 학생에게 동시에 발급해도 활성 코드는 한 건만 남는다`() {
        val candidates = List(CONCURRENCY) { index -> "C${index.toString().padStart(7, '0')}" }
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        val results = try {
            val futures = candidates.mapIndexed { index, candidate ->
                executor.submit<ConcurrentIssueResult> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching {
                        issue(
                            studentId = STUDENT_ID,
                            resetCode = candidate,
                            createdAt = BASE_TIME.plusSeconds(index.toLong())
                        )
                    }.fold(
                        onSuccess = { issued ->
                            ConcurrentIssueResult(issued, null)
                        },
                        onFailure = { failure ->
                            ConcurrentIssueResult(null, failure)
                        }
                    )
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        val successes = results.mapNotNull(ConcurrentIssueResult::issued)
        val failures = results.mapNotNull(ConcurrentIssueResult::failure)
        assertThat(successes).isNotEmpty
        assertThat(failures).allSatisfy { failure ->
            assertThat(failure)
                .isInstanceOf(DuplicateKeyException::class.java)
                .hasMessageContaining(MongoIndexInitializer.PASSWORD_RESET_STUDENT_ID_UNIQUE_INDEX_NAME)
        }
        val persistedIds = successes.map { issued -> requireNotNull(issued.id) }
        assertThat(persistedIds).containsOnly(persistedIds.first())
        assertThat(successes).allSatisfy { issued ->
            assertThat(issued.resetCode).isIn(candidates)
        }

        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        val stored = findByStudentId(STUDENT_ID).single()
        assertThat(stored.resetCode).isIn(successes.map(PasswordResetCode::resetCode))
        assertThat(candidates.count(::existsByResetCode)).isEqualTo(1)
    }

    @Test
    fun `서비스에서 같은 학생에게 동시에 발급하면 모든 요청을 처리하고 활성 코드 한 건만 남긴다`() {
        replaceStudentIdIndexWithCompatibleName()
        val student = savePasswordStudent()
        val sentCodes = ConcurrentLinkedQueue<String>()
        val emailService = mockk<EmailService>(relaxed = true)
        every {
            emailService.sendTemplateEmail(any(), any(), any(), any())
        } answers {
            sentCodes.add(arg<Map<String, Any>>(3).getValue("resetCode") as String)
            Unit
        }
        val authService = createAuthService(
            generator = CounterPasswordResetCodeGenerator(),
            emailService = emailService
        )
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        val failures = try {
            val futures = List(CONCURRENCY) {
                executor.submit<Throwable?> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching {
                        authService.findPassword(
                            email = requireNotNull(student.email),
                            bojId = requireNotNull(student.bojId).value
                        )
                    }.exceptionOrNull()
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.map { future -> future.get(10, TimeUnit.SECONDS) }.filterNotNull()
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(failures).isEmpty()
        assertThat(sentCodes).hasSize(CONCURRENCY)
        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        val stored = findByStudentId(requireNotNull(student.id)).single()
        assertThat(stored.resetCode).isIn(sentCodes)
        assertThat(sentCodes.count(::existsByResetCode)).isEqualTo(1)
    }

    @Test
    fun `다른 학생의 재설정 코드와 충돌하면 기존 문서를 보존하고 충돌을 전달한다`() {
        val existing = issue(
            studentId = "existing-student",
            resetCode = "COLLIDE1",
            createdAt = BASE_TIME
        )

        assertThatThrownBy {
            issue(
                studentId = "new-student",
                resetCode = "COLLIDE1",
                createdAt = BASE_TIME.plusMinutes(1)
            )
        }
            .isInstanceOf(DuplicateKeyException::class.java)
            .hasMessageContaining(MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME)

        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        assertThat(findByStudentId("existing-student")).containsExactly(existing)
        assertThat(findByStudentId("new-student")).isEmpty()
    }

    @Test
    fun `서비스는 다른 학생의 코드와 충돌하면 새 후보로 발급한다`() {
        issue(
            studentId = "existing-student",
            resetCode = "COLLIDE1",
            createdAt = BASE_TIME
        )
        val student = savePasswordStudent()
        val sentCodes = ConcurrentLinkedQueue<String>()
        val emailService = mockk<EmailService>(relaxed = true)
        every {
            emailService.sendTemplateEmail(any(), any(), any(), any())
        } answers {
            sentCodes.add(arg<Map<String, Any>>(3).getValue("resetCode") as String)
            Unit
        }
        val authService = createAuthService(
            generator = QueuePasswordResetCodeGenerator(listOf("COLLIDE1", "RETRY001")),
            emailService = emailService
        )

        authService.findPassword(
            email = requireNotNull(student.email),
            bojId = requireNotNull(student.bojId).value
        )

        assertThat(findByStudentId("existing-student").single().resetCode).isEqualTo("COLLIDE1")
        assertThat(findByStudentId(requireNotNull(student.id)).single().resetCode).isEqualTo("RETRY001")
        assertThat(sentCodes).containsExactly("RETRY001")
    }

    @Test
    fun `서비스는 코드 충돌 재시도를 소진하면 문서와 메일을 만들지 않는다`() {
        val collisionCodes = List(MAX_ISSUE_ATTEMPTS) { index ->
            "USED${(index + 1).toString().padStart(4, '0')}"
        }
        collisionCodes.forEachIndexed { index, resetCode ->
            issue(
                studentId = "existing-student-$index",
                resetCode = resetCode,
                createdAt = BASE_TIME.plusSeconds(index.toLong())
            )
        }
        val student = savePasswordStudent()
        val sentCodes = ConcurrentLinkedQueue<String>()
        val emailService = mockk<EmailService>(relaxed = true)
        every {
            emailService.sendTemplateEmail(any(), any(), any(), any())
        } answers {
            sentCodes.add(arg<Map<String, Any>>(3).getValue("resetCode") as String)
            Unit
        }
        val authService = createAuthService(
            generator = QueuePasswordResetCodeGenerator(collisionCodes),
            emailService = emailService
        )

        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.findPassword(
                email = requireNotNull(student.email),
                bojId = requireNotNull(student.bojId).value
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(findByStudentId(requireNotNull(student.id))).isEmpty()
        assertThat(passwordResetCodeRepository.count()).isEqualTo(MAX_ISSUE_ATTEMPTS.toLong())
        assertThat(sentCodes).isEmpty()
    }

    @Test
    fun `현재 발급의 메일 전송이 실패하면 저장한 코드를 삭제한다`() {
        val student = savePasswordStudent()
        val mailException = IllegalStateException("mail send failed")
        val emailService = mockk<EmailService>(relaxed = true)
        every {
            emailService.sendTemplateEmail(any(), any(), any(), any())
        } throws mailException
        val authService = createAuthService(
            generator = QueuePasswordResetCodeGenerator(listOf("MAIL0001")),
            emailService = emailService
        )

        val thrown = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            authService.findPassword(
                email = requireNotNull(student.email),
                bojId = requireNotNull(student.bojId).value
            )
        }

        assertThat(thrown).isSameAs(mailException)
        assertThat(passwordResetCodeRepository.count()).isZero()
    }

    @Test
    fun `이전 발급의 메일 실패 정리는 더 최신 코드를 삭제하지 않는다`() {
        issue(
            studentId = STUDENT_ID,
            resetCode = "MAIL0001",
            createdAt = BASE_TIME
        )
        issue(
            studentId = STUDENT_ID,
            resetCode = "MAIL0002",
            createdAt = BASE_TIME.plusMinutes(1)
        )

        assertThat(passwordResetCodeRepository.deleteIssuedCode(STUDENT_ID, "MAIL0001")).isFalse()
        assertThat(findByStudentId(STUDENT_ID).single().resetCode).isEqualTo("MAIL0002")

        assertThat(passwordResetCodeRepository.deleteIssuedCode(STUDENT_ID, "MAIL0002")).isTrue()
        assertThat(passwordResetCodeRepository.count()).isZero()
    }

    @Test
    fun `이전 발급의 지연된 메일 실패는 서비스가 저장한 최신 코드를 삭제하지 않는다`() {
        val student = savePasswordStudent()
        val firstMailEntered = CountDownLatch(1)
        val releaseFirstMail = CountDownLatch(1)
        val firstMailException = IllegalStateException("first mail send failed")
        val emailService = mockk<EmailService>(relaxed = true)
        every {
            emailService.sendTemplateEmail(any(), any(), any(), any())
        } answers {
            when (arg<Map<String, Any>>(3).getValue("resetCode")) {
                "MAIL0001" -> {
                    firstMailEntered.countDown()
                    check(releaseFirstMail.await(10, TimeUnit.SECONDS))
                    throw firstMailException
                }

                else -> Unit
            }
        }
        val authService = createAuthService(
            generator = QueuePasswordResetCodeGenerator(listOf("MAIL0001", "MAIL0002")),
            emailService = emailService
        )
        val executor = Executors.newSingleThreadExecutor()

        val firstFailure = try {
            val firstRequest = executor.submit<Throwable?> {
                runCatching {
                    authService.findPassword(
                        email = requireNotNull(student.email),
                        bojId = requireNotNull(student.bojId).value
                    )
                }.exceptionOrNull()
            }

            assertThat(firstMailEntered.await(10, TimeUnit.SECONDS)).isTrue()
            authService.findPassword(
                email = requireNotNull(student.email),
                bojId = requireNotNull(student.bojId).value
            )
            releaseFirstMail.countDown()
            firstRequest.get(10, TimeUnit.SECONDS)
        } finally {
            releaseFirstMail.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(firstFailure).isSameAs(firstMailException)
        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        assertThat(findByStudentId(requireNotNull(student.id)).single().resetCode).isEqualTo("MAIL0002")
    }

    private fun issue(
        studentId: String,
        resetCode: String,
        createdAt: LocalDateTime
    ): PasswordResetCode {
        return passwordResetCodeRepository.issueForStudent(
            studentId = studentId,
            resetCode = resetCode,
            expiresAt = createdAt.plusMinutes(30),
            createdAt = createdAt
        )
    }

    private fun findByStudentId(studentId: String): List<PasswordResetCode> {
        return mongoTemplate.find(
            Query.query(Criteria.where("studentId").`is`(studentId)),
            PasswordResetCode::class.java
        )
    }

    private fun existsByResetCode(resetCode: String): Boolean {
        return mongoTemplate.exists(
            Query.query(Criteria.where("resetCode").`is`(resetCode)),
            PasswordResetCode::class.java
        )
    }

    private fun replaceStudentIdIndexWithCompatibleName() {
        val indexOperations = mongoTemplate.indexOps(PasswordResetCode::class.java)
        indexOperations.dropIndex(MongoIndexInitializer.PASSWORD_RESET_STUDENT_ID_UNIQUE_INDEX_NAME)
        indexOperations.ensureIndex(
            Index()
                .on("studentId", Sort.Direction.ASC)
                .unique()
                .named(COMPATIBLE_STUDENT_ID_INDEX_NAME)
        )

        MongoIndexInitializer(mongoTemplate).ensureIndexes()

        assertThat(indexOperations.indexInfo.mapNotNull { it.name })
            .contains(COMPATIBLE_STUDENT_ID_INDEX_NAME)
            .doesNotContain(MongoIndexInitializer.PASSWORD_RESET_STUDENT_ID_UNIQUE_INDEX_NAME)
    }

    private fun savePasswordStudent(): Student {
        return studentRepository.save(
            Student(
                id = STUDENT_ID,
                nickname = Nickname("issueuser"),
                provider = Provider.BOJ,
                providerId = "issueuser",
                email = "issue@example.com",
                bojId = BojId("issueuser"),
                password = "encoded-password",
                rating = 1234,
                currentTier = Tier.SILVER,
                role = Role.USER
            )
        )
    }

    private fun createAuthService(
        generator: PasswordResetCodeGenerator,
        emailService: EmailService
    ): AuthService {
        return AuthService(
            solvedAcClient = mockk<SolvedAcClient>(relaxed = true),
            studentRepository = studentRepository,
            jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true),
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            emailService = emailService,
            passwordResetCodeRepository = passwordResetCodeRepository,
            passwordResetCodeGenerator = generator,
            refreshTokenService = mockk<RefreshTokenService>(relaxed = true),
            bojOwnershipVerificationService = mockk<BojOwnershipVerificationService>(relaxed = true)
        )
    }

    private class CounterPasswordResetCodeGenerator : PasswordResetCodeGenerator {
        private val sequence = AtomicInteger()

        override fun generate(): String {
            return "C${sequence.getAndIncrement().toString().padStart(7, '0')}"
        }
    }

    private class QueuePasswordResetCodeGenerator(
        candidates: List<String>
    ) : PasswordResetCodeGenerator {
        private val candidates = ConcurrentLinkedQueue(candidates)

        override fun generate(): String {
            return checkNotNull(candidates.poll()) {
                "테스트 재설정 코드 후보가 부족합니다."
            }
        }
    }

    private data class ConcurrentIssueResult(
        val issued: PasswordResetCode?,
        val failure: Throwable?
    )

    companion object {
        private const val STUDENT_ID = "student-a"
        private const val CONCURRENCY = 20
        private const val MAX_ISSUE_ATTEMPTS = 5
        private const val COMPATIBLE_STUDENT_ID_INDEX_NAME = "legacy_password_reset_student_id"
        private val BASE_TIME = LocalDateTime.of(2026, 7, 29, 10, 0)
        private val testDatabaseName =
            "didimlog-password-reset-issue-${UUID.randomUUID().toString().replace("-", "")}"

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
