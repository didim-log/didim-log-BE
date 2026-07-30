package com.didimlog.application.log

import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.domain.Log
import com.didimlog.domain.Student
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.util.UUID
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
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DataMongoTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("로그 불변 소유권 통합 테스트")
class LogOwnershipConsistencyIntegrationTest {

    @Autowired
    private lateinit var logRepository: LogRepository

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private lateinit var logService: LogService

    @BeforeEach
    fun setUp() {
        logRepository.deleteAll()
        studentRepository.deleteAll()
        studentRepository.saveAll(
            listOf(
                student("original-student-id", "original", "original-provider"),
                student("replacement-student-id", "replacement", "replacement-provider")
            )
        )
        logService = LogService(
            logRepository = logRepository,
            logFeedbackRepository = MongoLogFeedbackRepository(mongoTemplate),
            studentRepository = studentRepository,
            studentLifecycleCoordinator = ImmediateCredentialSessionCoordinator()
        )
    }

    @AfterAll
    fun tearDown() {
        logRepository.deleteAll()
        studentRepository.deleteAll()
    }

    @Test
    @DisplayName("같은 BOJ ID를 가진 다른 학생은 기존 로그를 사용할 수 없다")
    fun `reused boj id does not transfer log ownership`() {
        val saved = logService.createLog(
            title = "소유권 로그",
            content = "원래 학생의 기록",
            code = "fun main() = Unit",
            studentId = "original-student-id",
            bojId = "sharedboj"
        )
        val logId = requireNotNull(saved.id)

        assertThatThrownBy {
            logService.getLogTemplate(logId, "replacement-student-id")
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.ACCESS_DENIED }

        assertThatThrownBy {
            logService.updateFeedback(
                logId = logId,
                requesterStudentId = "replacement-student-id",
                status = AiFeedbackStatus.LIKE
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.ACCESS_DENIED }

        assertThat(logService.getLogTemplate(logId, "original-student-id"))
            .isEqualTo("원래 학생의 기록")
    }

    @Test
    @DisplayName("불변 소유자가 없는 이전 로그는 BOJ ID가 같아도 사용자 접근을 거부한다")
    fun `legacy log without immutable owner is denied`() {
        val legacyLog = logRepository.save(
            Log(
                title = LogTitle("이전 로그"),
                content = LogContent("이전 기록"),
                code = LogCode("fun main() = Unit"),
                bojId = BojId("sharedboj")
            )
        )

        assertThatThrownBy {
            logService.getLogTemplate(requireNotNull(legacyLog.id), "shared-boj")
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.ACCESS_DENIED }
    }

    @Test
    @DisplayName("피드백 부분 갱신은 기존 AI 리뷰 필드를 보존한다")
    fun `feedback partial update preserves ai review fields`() {
        val saved = logRepository.save(
            Log(
                title = LogTitle("AI 리뷰 로그"),
                content = LogContent("부분 갱신으로 보존할 로그"),
                code = LogCode("fun main() = Unit"),
                studentId = "original-student-id",
                bojId = BojId("original"),
                aiReview = AiReview("기존 AI 리뷰"),
                aiReviewStatus = AiReviewStatus.COMPLETED,
                aiReviewDurationMillis = 321L,
                promptVersion = "v2.0"
            )
        )

        val updated = logService.updateFeedback(
            logId = requireNotNull(saved.id),
            requesterStudentId = "original-student-id",
            status = AiFeedbackStatus.DISLIKE,
            reason = "설명이 부족합니다."
        )

        assertThat(updated.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.DISLIKE)
        assertThat(updated.aiFeedbackReason).isEqualTo("설명이 부족합니다.")
        assertThat(updated.aiReview).isEqualTo(saved.aiReview)
        assertThat(updated.aiReviewStatus).isEqualTo(saved.aiReviewStatus)
        assertThat(updated.aiReviewDurationMillis).isEqualTo(saved.aiReviewDurationMillis)
        assertThat(updated.promptVersion).isEqualTo(saved.promptVersion)
    }

    private fun student(id: String, nickname: String, providerId: String): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = providerId,
            currentTier = Tier.BRONZE
        )
    }

    companion object {
        private val databaseName =
            "didimlog-log-owner-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                val port = System.getenv("TEST_MONGO_PORT") ?: "27017"
                "mongodb://localhost:$port/$databaseName"
            }
        }
    }
}
