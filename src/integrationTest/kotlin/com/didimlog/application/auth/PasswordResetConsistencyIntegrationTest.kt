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
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.InvalidPasswordException
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.mockk
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
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

    private fun createAuthService(
        encoder: PasswordEncoder,
        solvedAcClient: SolvedAcClient = mockk(relaxed = true),
        credentialSessionCoordinator: CredentialSessionCoordinator = ImmediateCredentialSessionCoordinator()
    ): AuthService {
        return AuthService(
            solvedAcClient = solvedAcClient,
            studentRepository = studentRepository,
            jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true),
            passwordEncoder = encoder,
            emailService = mockk<EmailService>(relaxed = true),
            passwordResetCodeRepository = passwordResetCodeRepository,
            passwordResetCodeGenerator = mockk<PasswordResetCodeGenerator>(relaxed = true),
            refreshTokenService = mockk<RefreshTokenService>(relaxed = true),
            bojOwnershipVerificationService = mockk<BojOwnershipVerificationService>(relaxed = true),
            credentialSessionCoordinator = credentialSessionCoordinator
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
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
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
        assertThat(updatedStudent.credentialVersion).isEqualTo(student.credentialVersion + 1)
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
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
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
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
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
    fun `비밀번호 변경 전에 발급한 코드는 새 비밀번호를 덮어쓰지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val changedPassword = "changed-password"
        assertThat(
            studentRepository.updatePasswordById(
                requireNotNull(student.id),
                changedPassword,
                student.credentialVersion,
                requireNotNull(student.bojId)
            )
        ).isTrue()

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        val currentStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(currentStudent.password).isEqualTo(changedPassword)
        assertThat(currentStudent.credentialVersion).isEqualTo(student.credentialVersion + 1)
        assertThat(passwordResetCodeRepository.findByResetCode(RESET_CODE)).isPresent
        assertThat(passwordEncoder.encodeCount.get()).isZero()
    }

    @Test
    fun `권한 승격 전에 발급한 코드는 승격된 계정의 비밀번호를 변경하지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val promotedStudent = studentRepository.save(
            student.copy(
                role = Role.ADMIN,
                credentialVersion = student.credentialVersion + 1
            )
        )

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        val currentStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(currentStudent.role).isEqualTo(Role.ADMIN)
        assertThat(currentStudent.password).isEqualTo(OLD_PASSWORD)
        assertThat(currentStudent.credentialVersion).isEqualTo(promotedStudent.credentialVersion)
        assertThat(passwordResetCodeRepository.findByResetCode(RESET_CODE)).isPresent
        assertThat(passwordEncoder.encodeCount.get()).isZero()
    }

    @Test
    fun `BOJ ID 변경 전에 발급한 코드는 변경된 계정의 비밀번호를 바꾸지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val changedStudent = studentRepository.save(
            student.copy(bojId = BojId("changed_user"))
        )

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        val currentStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(currentStudent.bojId).isEqualTo(changedStudent.bojId)
        assertThat(currentStudent.password).isEqualTo(OLD_PASSWORD)
        assertThat(passwordResetCodeRepository.findByResetCode(RESET_CODE)).isPresent
        assertThat(passwordEncoder.encodeCount.get()).isZero()
    }

    @Test
    fun `세션 잠금 충돌 시 재설정 코드를 보존한다`() {
        assertCoordinatorFailurePreservesResetCode(ErrorCode.SESSION_STATE_CONFLICT)
    }

    @Test
    fun `세션 상태 저장소 장애 시 재설정 코드를 보존한다`() {
        assertCoordinatorFailurePreservesResetCode(ErrorCode.SESSION_STATE_UNAVAILABLE)
    }

    @Test
    fun `조회 뒤 같은 코드의 소유자가 바뀌면 다른 학생 코드를 소비하지 않는다`() {
        val snapshotStudent = saveStudent()
        val replacementStudent = studentRepository.save(
            snapshotStudent.copy(
                id = "replacement-${UUID.randomUUID()}",
                nickname = Nickname("replacement"),
                providerId = "replacement-user",
                email = "replacement@example.com",
                bojId = BojId("replacement_user"),
                documentVersion = null
            )
        )
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(snapshotStudent.id),
                credentialVersion = snapshotStudent.credentialVersion,
                bojId = requireNotNull(snapshotStudent.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val replacingAuthService = createAuthService(
            encoder = passwordEncoder,
            credentialSessionCoordinator = BeforeActionCredentialSessionCoordinator {
                val updateResult = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("resetCode").`is`(RESET_CODE)),
                    Update.update("studentId", requireNotNull(replacementStudent.id)),
                    PasswordResetCode::class.java
                )
                assertThat(updateResult.modifiedCount).isEqualTo(1)
            }
        )

        val exception = assertThrows<BusinessException> {
            replacingAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("유효하지 않은 재설정 코드")
        val preservedCode = passwordResetCodeRepository.findByResetCode(RESET_CODE).orElseThrow()
        assertThat(preservedCode.studentId).isEqualTo(replacementStudent.id)
        assertThat(passwordResetCodeRepository.count()).isEqualTo(1)
        assertThat(passwordEncoder.encodeCount.get()).isZero()
        assertThat(studentRepository.findById(requireNotNull(snapshotStudent.id)).orElseThrow().password)
            .isEqualTo(OLD_PASSWORD)
        assertThat(studentRepository.findById(requireNotNull(replacementStudent.id)).orElseThrow().password)
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
        assertThat(passwordEncoder.encodeCount.get()).isZero()
    }

    @Test
    fun `비밀번호 인코딩 중 변경된 학생 필드를 덮어쓰지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
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
    fun `비밀번호 CAS 충돌은 코드를 소비하고 새 코드 발급을 요구한다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val blockingEncoder = BlockingPasswordEncoder()
        val conflictingAuthService = createAuthService(blockingEncoder)
        val executor = Executors.newSingleThreadExecutor()

        val resetFailure = try {
            val resetFuture = executor.submit {
                conflictingAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
            }

            assertThat(blockingEncoder.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val versionUpdate = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(student.id)),
                Update().inc("credentialVersion", 1),
                Student::class.java
            )
            assertThat(versionUpdate.modifiedCount).isEqualTo(1)

            blockingEncoder.release.countDown()
            assertThrows<ExecutionException> {
                resetFuture.get(10, TimeUnit.SECONDS)
            }.cause
        } finally {
            blockingEncoder.release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(resetFailure).isInstanceOf(BusinessException::class.java)
        val exception = resetFailure as BusinessException
        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        assertThat(exception.errorCode.retryable).isFalse()
        assertThat(exception.message).contains("새 재설정 코드")
        assertThat(passwordResetCodeRepository.count()).isZero()
        val preservedStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(preservedStudent.password).isEqualTo(OLD_PASSWORD)
        assertThat(preservedStudent.credentialVersion).isEqualTo(student.credentialVersion + 1)
    }

    @Test
    fun `비밀번호 인코딩 중 BOJ ID가 바뀌면 이전 코드의 비밀번호 갱신을 거절한다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val blockingEncoder = BlockingPasswordEncoder()
        val conflictingAuthService = createAuthService(blockingEncoder)
        val executor = Executors.newSingleThreadExecutor()

        val resetFailure = try {
            val resetFuture = executor.submit {
                conflictingAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
            }

            assertThat(blockingEncoder.entered.await(10, TimeUnit.SECONDS)).isTrue()
            val bojIdUpdate = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(student.id)),
                Update.update("bojId", "changed_during_reset"),
                Student::class.java
            )
            assertThat(bojIdUpdate.modifiedCount).isEqualTo(1)

            blockingEncoder.release.countDown()
            assertThrows<ExecutionException> {
                resetFuture.get(10, TimeUnit.SECONDS)
            }.cause
        } finally {
            blockingEncoder.release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(resetFailure).isInstanceOf(BusinessException::class.java)
        assertThat((resetFailure as BusinessException).errorCode)
            .isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        assertThat(passwordResetCodeRepository.count()).isZero()
        val preservedStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(preservedStudent.bojId).isEqualTo(BojId("changed_during_reset"))
        assertThat(preservedStudent.password).isEqualTo(OLD_PASSWORD)
        assertThat(preservedStudent.credentialVersion).isEqualTo(student.credentialVersion)
    }

    @Test
    fun `로그인 프로필 동기화와 재설정 뒤 지연 로그인을 차단한다`() {
        val student = saveStudent(password = encoded(OLD_LOGIN_PASSWORD))
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val blockingSolvedAcClient = BlockingSolvedAcClient(
            SolvedAcUserResponse(
                handle = requireNotNull(student.bojId).value,
                rating = SYNCED_RATING,
                tier = SolvedAcTierLevel.fromRating(SYNCED_RATING).value
            )
        )
        val concurrentAuthService = createAuthService(passwordEncoder, blockingSolvedAcClient)
        val executor = Executors.newSingleThreadExecutor()

        val loginFailure = try {
            val loginFuture = executor.submit<AuthService.AuthResult> {
                concurrentAuthService.login(requireNotNull(student.bojId).value, OLD_LOGIN_PASSWORD)
            }

            assertThat(blockingSolvedAcClient.entered.await(10, TimeUnit.SECONDS)).isTrue()
            concurrentAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
            blockingSolvedAcClient.release.countDown()
            assertThrows<ExecutionException> {
                loginFuture.get(10, TimeUnit.SECONDS)
            }.cause
        } finally {
            blockingSolvedAcClient.release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        val updatedStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        val expectedTierLevel = SolvedAcTierLevel.fromRating(SYNCED_RATING)
        assertThat(updatedStudent.password).isEqualTo(encoded(NEW_PASSWORD))
        assertThat(updatedStudent.rating).isEqualTo(SYNCED_RATING)
        assertThat(updatedStudent.solvedAcTierLevel).isEqualTo(expectedTierLevel)
        assertThat(updatedStudent.currentTier).isEqualTo(Tier.fromRating(SYNCED_RATING))
        assertThat(updatedStudent.nickname).isEqualTo(student.nickname)
        assertThat(studentRepository.count()).isEqualTo(1)
        assertThat(loginFailure).isInstanceOf(BusinessException::class.java)
        assertThat((loginFailure as BusinessException).errorCode)
            .isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
    }

    @Test
    fun `없는 학생의 프로필 부분 갱신은 문서를 생성하지 않는다`() {
        val expectedTierLevel = SolvedAcTierLevel.fromRating(SYNCED_RATING)

        val updatedStudent = studentRepository.updateSolvedAcProfileById(
            studentId = "missing-student",
            expectedBojId = BojId("missingboj"),
            rating = SYNCED_RATING,
            solvedAcTierLevel = expectedTierLevel,
            currentTier = Tier.fromRating(SYNCED_RATING)
        )

        assertThat(updatedStudent).isNull()
        assertThat(studentRepository.count()).isZero()
    }

    @Test
    fun `인코딩 실패 뒤에도 소비한 코드를 복원하지 않는다`() {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
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

    private fun assertCoordinatorFailurePreservesResetCode(errorCode: ErrorCode) {
        val student = saveStudent()
        passwordResetCodeRepository.save(
            PasswordResetCode(
                resetCode = RESET_CODE,
                studentId = requireNotNull(student.id),
                credentialVersion = student.credentialVersion,
                bojId = requireNotNull(student.bojId).value,
                expiresAt = LocalDateTime.now().plusMinutes(30)
            )
        )
        val rejectingAuthService = createAuthService(
            encoder = passwordEncoder,
            credentialSessionCoordinator = RejectingCredentialSessionCoordinator(errorCode)
        )

        val exception = assertThrows<BusinessException> {
            rejectingAuthService.resetPassword(RESET_CODE, NEW_PASSWORD)
        }

        assertThat(exception.errorCode).isEqualTo(errorCode)
        assertThat(passwordResetCodeRepository.findByResetCode(RESET_CODE)).isPresent
        assertThat(passwordEncoder.encodeCount.get()).isZero()
        assertThat(studentRepository.findById(requireNotNull(student.id)).orElseThrow().password)
            .isEqualTo(OLD_PASSWORD)
    }

    private fun saveStudent(password: String = OLD_PASSWORD): Student {
        return studentRepository.save(
            Student(
                id = "student-${UUID.randomUUID()}",
                nickname = Nickname("reset-user"),
                provider = Provider.BOJ,
                providerId = "reset-user",
                email = "reset@example.com",
                bojId = BojId("reset_user"),
                password = password,
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

    private class BlockingSolvedAcClient(
        private val response: SolvedAcUserResponse
    ) : SolvedAcClient {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun fetchProblem(problemId: Int): SolvedAcProblemResponse {
            error("이 테스트에서는 문제 조회를 사용하지 않는다.")
        }

        override fun fetchUser(bojId: BojId): SolvedAcUserResponse {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            return response
        }
    }

    private class RejectingCredentialSessionCoordinator(
        private val errorCode: ErrorCode
    ) : CredentialSessionCoordinator {
        override fun <T> execute(studentId: String, action: () -> T): T {
            throw BusinessException(errorCode)
        }

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            throw BusinessException(errorCode)
        }
    }

    private class BeforeActionCredentialSessionCoordinator(
        private val beforeAction: () -> Unit
    ) : CredentialSessionCoordinator {
        override fun <T> execute(studentId: String, action: () -> T): T {
            beforeAction()
            return action()
        }

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            beforeAction()
            return action()
        }
    }

    companion object {
        private const val RESET_CODE = "RESET001"
        private const val NEW_PASSWORD = "NewPassword123!"
        private const val OLD_LOGIN_PASSWORD = "OldPassword123!"
        private const val OLD_PASSWORD = "old-encoded-password"
        private const val UPDATED_RATING = 2345
        private const val SYNCED_RATING = 2450
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
