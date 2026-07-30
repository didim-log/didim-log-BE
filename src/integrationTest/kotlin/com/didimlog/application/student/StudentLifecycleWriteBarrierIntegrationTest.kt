package com.didimlog.application.student

import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.problem.ProblemService
import com.didimlog.application.retrospective.RetrospectiveService
import com.didimlog.application.template.TemplateService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.auth.CredentialSessionLockConfig
import com.didimlog.infra.auth.RedisCredentialSessionCoordinator
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DataMongoTest
@ImportAutoConfiguration(RedisAutoConfiguration::class)
@Import(RedisCredentialSessionCoordinator::class, CredentialSessionLockConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("학생 생명주기 쓰기 차단 통합 테스트")
class StudentLifecycleWriteBarrierIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

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

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var studentLifecycleCoordinator: StudentLifecycleCoordinator

    private val lockKeys = mutableSetOf<String>()

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
    }

    @AfterEach
    fun cleanRedis() {
        if (lockKeys.isNotEmpty()) {
            redisTemplate.delete(lockKeys)
        }
        lockKeys.clear()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `템플릿 생성 중 계정 삭제는 충돌하고 재시도하면 생성 데이터까지 제거한다`() {
        val studentId = saveStudent("template-writer")
        trackLock(studentId)
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val blockingTemplateRepository = object : TemplateRepository by templateRepository {
            override fun <S : Template> save(entity: S): S {
                saveEntered.countDown()
                check(releaseSave.await(10, TimeUnit.SECONDS)) {
                    "템플릿 저장 해제 신호를 기다리지 못했습니다."
                }
                return templateRepository.save(entity)
            }
        }
        val templateService = TemplateService(
            templateRepository = blockingTemplateRepository,
            problemService = mockk<ProblemService>(),
            studentRepository = studentRepository,
            studentLifecycleCoordinator = studentLifecycleCoordinator
        )
        val accountDeletionService = accountDeletionService()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val createTask = executor.submit<Template> {
                templateService.createTemplate(studentId, "경합 템플릿", "경합 상황에서 저장할 템플릿")
            }
            assertThat(saveEntered.await(10, TimeUnit.SECONDS)).isTrue()

            val conflict = assertThrows<BusinessException> {
                accountDeletionService.deleteAccount(studentId)
            }
            assertThat(conflict.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
            assertThat(studentRepository.existsById(studentId)).isTrue()
            assertThat(templateRepository.findByStudentId(studentId)).isEmpty()

            releaseSave.countDown()
            val created = createTask.get(10, TimeUnit.SECONDS)
            assertThat(templateRepository.existsById(requireNotNull(created.id))).isTrue()

            accountDeletionService.deleteAccount(studentId)

            assertThat(studentRepository.existsById(studentId)).isFalse()
            assertThat(templateRepository.findByStudentId(studentId)).isEmpty()
        } finally {
            releaseSave.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `계정 삭제 중 신규 회고는 충돌하고 삭제 완료 뒤 다시 만들 수 없다`() {
        val studentId = saveStudent("deleting-writer")
        trackLock(studentId)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val blockingRetrospectiveRepository =
            object : RetrospectiveRepository by retrospectiveRepository {
                override fun deleteAllByStudentId(studentId: String) {
                    cleanupEntered.countDown()
                    check(releaseCleanup.await(10, TimeUnit.SECONDS)) {
                        "계정 데이터 정리 해제 신호를 기다리지 못했습니다."
                    }
                    retrospectiveRepository.deleteAllByStudentId(studentId)
                }
            }
        val accountDeletionService = accountDeletionService(blockingRetrospectiveRepository)
        val retrospectiveService = RetrospectiveService(
            retrospectiveRepository = retrospectiveRepository,
            studentRepository = studentRepository,
            problemRepository = problemRepository,
            studentLifecycleCoordinator = studentLifecycleCoordinator
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val deletionTask = executor.submit {
                accountDeletionService.deleteAccount(studentId)
            }
            assertThat(cleanupEntered.await(10, TimeUnit.SECONDS)).isTrue()

            val conflict = assertThrows<BusinessException> {
                retrospectiveService.writeRetrospective(
                    studentId = studentId,
                    problemId = "1000",
                    content = "삭제와 겹쳐 저장되면 안 되는 회고 내용입니다.",
                    summary = "삭제 경합"
                )
            }
            assertThat(conflict.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
            assertThat(retrospectiveRepository.findAllByStudentId(studentId)).isEmpty()

            releaseCleanup.countDown()
            deletionTask.get(10, TimeUnit.SECONDS)

            val deleted = assertThrows<BusinessException> {
                retrospectiveService.writeRetrospective(
                    studentId = studentId,
                    problemId = "1000",
                    content = "삭제 뒤 저장되면 안 되는 회고 내용입니다.",
                    summary = "삭제 완료"
                )
            }
            assertThat(deleted.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
            assertThat(studentRepository.existsById(studentId)).isFalse()
            assertThat(retrospectiveRepository.findAllByStudentId(studentId)).isEmpty()
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
        }
    }

    private fun accountDeletionService(
        retrospectives: RetrospectiveRepository = retrospectiveRepository
    ): AccountDeletionService {
        return AccountDeletionService(
            studentRepository = studentRepository,
            retrospectiveRepository = retrospectives,
            feedbackRepository = feedbackRepository,
            logRepository = logRepository,
            templateRepository = templateRepository,
            passwordResetCodeRepository = passwordResetCodeRepository,
            refreshTokenService = mockk<RefreshTokenService>(relaxed = true),
            studentLifecycleCoordinator = studentLifecycleCoordinator
        )
    }

    private fun saveStudent(id: String): String {
        val student = studentRepository.save(
            Student(
                id = id,
                nickname = Nickname("writer1"),
                provider = Provider.BOJ,
                providerId = "$id-provider",
                currentTier = Tier.BRONZE
            )
        )
        return requireNotNull(student.id)
    }

    private fun trackLock(studentId: String) {
        lockKeys += "credential:session:lock:$studentId"
    }

    companion object {
        private val testDatabaseName =
            "didimlog-write-barrier-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun dataProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                val port = System.getenv("TEST_MONGO_PORT") ?: "27017"
                "mongodb://localhost:$port/$testDatabaseName"
            }
            registry.add("spring.data.redis.host") { "127.0.0.1" }
            registry.add("spring.data.redis.port") {
                System.getenv("TEST_REDIS_PORT") ?: "6379"
            }
        }
    }
}
