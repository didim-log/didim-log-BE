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
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.InvalidPasswordException
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import io.mockk.mockk
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
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

@DisplayName("비밀번호 재설정 정합성 통합 테스트")
@DataMongoTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PasswordResetConsistencyIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var passwordResetCodeRepository: PasswordResetCodeRepository

    private lateinit var passwordEncoder: CountingPasswordEncoder
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        passwordEncoder = CountingPasswordEncoder()
        authService = createAuthService(passwordEncoder)
    }

    private fun createAuthService(encoder: PasswordEncoder): AuthService {
        return AuthService(
            solvedAcClient = mockk<SolvedAcClient>(relaxed = true),
            studentRepository = studentRepository,
            jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true),
            passwordEncoder = encoder,
            emailService = mockk<EmailService>(relaxed = true),
            passwordResetCodeRepository = passwordResetCodeRepository,
            refreshTokenService = mockk<RefreshTokenService>(relaxed = true),
            bojOwnershipVerificationService = mockk<BojOwnershipVerificationService>(relaxed = true)
        )
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `같은 코드를 동시에 소비하면 한 요청만 비밀번호를 변경한다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )

        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val ready = CountDownLatch(CONCURRENCY)
        val start = CountDownLatch(1)
        val results = try {
            val futures = List(CONCURRENCY) {
                executor.submit<Throwable?> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching {
                        authService.resetPassword(RESET_CODE, NEW_PASSWORD)
                    }.exceptionOrNull()
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

        assertThat(results.count { it == null }).isEqualTo(1)
        assertThat(results.filterNotNull()).hasSize(CONCURRENCY - 1).allSatisfy { exception ->
            assertThat(exception).isInstanceOf(BusinessException::class.java)
            assertThat((exception as BusinessException).errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
            assertThat(exception.message).contains("유효하지 않은 재설정 코드")
        }
        assertThat(passwordEncoder.encodeCount.get()).isEqualTo(1)
        assertThat(passwordResetCodeRepository.count()).isZero()

        val updatedStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(updatedStudent.password).isEqualTo(passwordEncoder.encoded(NEW_PASSWORD))
        assertThat(updatedStudent.nickname).isEqualTo(student.nickname)
        assertThat(updatedStudent.rating).isEqualTo(student.rating)

        assertThrows<BusinessException> {
            authService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }
    }

    @Test
    fun `만료된 코드는 제거하고 비밀번호를 변경하지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                expiresAt = LocalDateTime.now().minusSeconds(1)
            )
        )

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(exception.message).contains("만료된 재설정 코드")
        assertThat(passwordResetCodeRepository.count()).isZero()
        assertThat(passwordEncoder.encodeCount.get()).isZero()
        assertThat(studentRepository.findById(requireNotNull(student.id)).orElseThrow().password)
            .isEqualTo(OLD_PASSWORD)
    }

    @Test
    fun `정책에 맞지 않는 비밀번호는 코드를 소비하지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )

        assertThrows<InvalidPasswordException> {
            authService.resetPassword(RESET_CODE, "short123")
        }

        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        assertThat(passwordEncoder.encodeCount.get()).isZero()
        assertThat(studentRepository.findById(requireNotNull(student.id)).orElseThrow().password)
            .isEqualTo(OLD_PASSWORD)
    }

    @Test
    fun `학생이 없으면 소비한 코드를 복원하지 않는다`() {
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = "missing-student",
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )

        assertThrows<BusinessException> {
            authService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(passwordResetCodeRepository.count()).isZero()
        assertThat(passwordEncoder.encodeCount.get()).isEqualTo(1)
    }

    @Test
    fun `비밀번호 인코딩 중 변경된 학생 필드를 덮어쓰지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val blockingEncoder = BlockingPasswordEncoder()
        val blockingAuthService = createAuthService(blockingEncoder)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val resetFuture = executor.submit {
                blockingAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
            }

            assertThat(blockingEncoder.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val profileUpdate = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(student.id)),
                Update.update("rating", UPDATED_RATING),
                Student::class.java
            )
            assertThat(profileUpdate.matchedCount).isEqualTo(1)

            blockingEncoder.release.countDown()
            resetFuture.get(10, TimeUnit.SECONDS)
        } finally {
            blockingEncoder.release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        val updatedStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(updatedStudent.password).isEqualTo(encoded(NEW_PASSWORD))
        assertThat(updatedStudent.rating).isEqualTo(UPDATED_RATING)
        assertThat(updatedStudent.nickname).isEqualTo(student.nickname)
        assertThat(passwordResetCodeRepository.count()).isZero()
    }

    @Test
    fun `인코딩 실패 뒤에도 소비한 코드를 복원하지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val failingAuthService = createAuthService(
            object : PasswordEncoder {
                override fun encode(rawPassword: CharSequence): String {
                    throw IllegalStateException("password encoding failed")
                }

                override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean = false
            }
        )

        assertThrows<IllegalStateException> {
            failingAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(passwordResetCodeRepository.count()).isZero()
        assertThat(studentRepository.findById(requireNotNull(student.id)).orElseThrow().password)
            .isEqualTo(OLD_PASSWORD)
    }

    private fun saveStudent(): Student {
        return studentRepository.save(
            Student(
                id = "student-${UUID.randomUUID()}",
                nickname = Nickname("reset-user"),
                provider = Provider.BOJ,
                providerId = "reset-user",
                email = "reset@example.com",
                bojId = BojId("reset_user"),
                password = OLD_PASSWORD,
                rating = 1234,
                currentTier = Tier.SILVER,
                role = Role.USER
            )
        )
    }

    private class CountingPasswordEncoder : PasswordEncoder {
        val encodeCount = AtomicInteger()

        override fun encode(rawPassword: CharSequence): String {
            encodeCount.incrementAndGet()
            return PasswordResetConsistencyIntegrationTest.encoded(rawPassword.toString())
        }

        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
            return encoded(rawPassword.toString()) == encodedPassword
        }

        fun encoded(rawPassword: String): String =
            PasswordResetConsistencyIntegrationTest.encoded(rawPassword)
    }

    private class BlockingPasswordEncoder : PasswordEncoder {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun encode(rawPassword: CharSequence): String {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            return encoded(rawPassword.toString())
        }

        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
            return encoded(rawPassword.toString()) == encodedPassword
        }
    }

    companion object {
        private const val RESET_CODE = "RESET001"
        private const val NEW_PASSWORD = "NewPassword123!"
        private const val OLD_PASSWORD = "old-encoded-password"
        private const val UPDATED_RATING = 2345
        private const val CONCURRENCY = 20
        private val testDatabaseName =
            "didimlog-password-reset-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                val port = System.getenv("TEST_MONGO_PORT") ?: "27017"
                "mongodb://localhost:$port/$testDatabaseName"
            }
        }

        private fun encoded(rawPassword: String): String = "encoded::$rawPassword"
    }
}
