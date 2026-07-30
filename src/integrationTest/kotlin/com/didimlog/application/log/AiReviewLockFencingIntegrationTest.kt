package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.global.config.MongoConfig
import java.time.LocalDateTime
import java.util.UUID
import org.bson.Document
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
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DataMongoTest
@Import(MongoLogAiReviewLockRepository::class, MongoConfig::class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("AI 리뷰 잠금 소유권 통합 테스트")
class AiReviewLockFencingIntegrationTest {

    @Autowired
    private lateinit var logRepository: LogRepository

    @Autowired
    private lateinit var lockRepository: LogAiReviewLockRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun setUp() {
        logRepository.deleteAll()
    }

    @AfterAll
    fun tearDown() {
        logRepository.deleteAll()
    }

    @Test
    fun `만료된 이전 작업은 새 잠금의 결과를 완료할 수 없다`() {
        val logId = saveLog()
        val firstStartedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        val secondExpiresAt = firstStartedAt.plusSeconds(47)

        val firstLock = requireNotNull(
            lockRepository.tryAcquireLock(
                logId,
                firstStartedAt,
                firstStartedAt.plusSeconds(1)
            )
        )
        val secondLock = requireNotNull(
            lockRepository.tryAcquireLock(
                logId,
                firstStartedAt.plusSeconds(2),
                secondExpiresAt
            )
        )
        assertThat(secondLock.version).isGreaterThan(firstLock.version)

        assertThat(
            lockRepository.markCompleted(
                logId,
                firstLock,
                firstStartedAt.plusSeconds(3),
                "stale-review",
                100L
            )
        ).isFalse()
        val current = logRepository.findById(logId).orElseThrow()
        assertThat(current.aiReview).isNull()
        assertThat(current.aiReviewStatus).isEqualTo(AiReviewStatus.IN_PROGRESS)
        assertThat(current.aiReviewLockVersion).isEqualTo(secondLock.version)
        assertThat(current.aiReviewLockExpiresAt).isEqualTo(secondExpiresAt)

        assertThat(
            lockRepository.markCompleted(
                logId,
                secondLock,
                firstStartedAt.plusSeconds(3),
                "current-review",
                200L
            )
        ).isTrue()
        val completed = logRepository.findById(logId).orElseThrow()
        assertThat(completed.aiReview?.value).isEqualTo("current-review")
        assertThat(completed.aiReviewStatus).isEqualTo(AiReviewStatus.COMPLETED)
        assertThat(completed.aiReviewLockExpiresAt).isNull()
        assertThat(completed.aiReviewLockVersion).isEqualTo(secondLock.version)
    }

    @Test
    fun `만료된 이전 작업은 새 잠금을 실패 처리할 수 없다`() {
        val logId = saveLog()
        val firstStartedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        val secondExpiresAt = firstStartedAt.plusSeconds(47)

        val firstLock = requireNotNull(
            lockRepository.tryAcquireLock(
                logId,
                firstStartedAt,
                firstStartedAt.plusSeconds(1)
            )
        )
        val secondLock = requireNotNull(
            lockRepository.tryAcquireLock(
                logId,
                firstStartedAt.plusSeconds(2),
                secondExpiresAt
            )
        )
        assertThat(secondLock.version).isGreaterThan(firstLock.version)

        assertThat(
            lockRepository.markFailed(
                logId,
                firstLock,
                firstStartedAt.plusSeconds(3)
            )
        ).isFalse()
        val current = logRepository.findById(logId).orElseThrow()
        assertThat(current.aiReviewStatus).isEqualTo(AiReviewStatus.IN_PROGRESS)
        assertThat(current.aiReviewLockVersion).isEqualTo(secondLock.version)
        assertThat(current.aiReviewLockExpiresAt).isEqualTo(secondExpiresAt)

        assertThat(
            lockRepository.markFailed(
                logId,
                secondLock,
                firstStartedAt.plusSeconds(3)
            )
        ).isTrue()
        val failed = logRepository.findById(logId).orElseThrow()
        assertThat(failed.aiReviewStatus).isEqualTo(AiReviewStatus.FAILED)
        assertThat(failed.aiReviewLockExpiresAt).isNull()
        assertThat(failed.aiReviewLockVersion).isEqualTo(secondLock.version)
    }

    @Test
    fun `실행 전 갱신은 만료되지 않은 현재 잠금만 연장한다`() {
        val logId = saveLog()
        val startedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        val firstLock = requireNotNull(
            lockRepository.tryAcquireLock(
                logId,
                startedAt,
                startedAt.plusSeconds(1)
            )
        )

        assertThat(
            lockRepository.renewLock(
                logId,
                firstLock,
                startedAt.plusSeconds(2),
                startedAt.plusSeconds(47)
            )
        ).isFalse()

        val secondLock = requireNotNull(
            lockRepository.tryAcquireLock(
                logId,
                startedAt.plusSeconds(2),
                startedAt.plusSeconds(47)
            )
        )
        assertThat(secondLock.version).isGreaterThan(firstLock.version)
        assertThat(
            lockRepository.renewLock(
                logId,
                firstLock,
                startedAt.plusSeconds(3),
                startedAt.plusSeconds(55)
            )
        ).isFalse()
        assertThat(
            logRepository.findById(logId).orElseThrow()
                .aiReviewLockExpiresAt
        ).isEqualTo(startedAt.plusSeconds(47))

        assertThat(
            lockRepository.renewLock(
                logId,
                secondLock,
                startedAt.plusSeconds(3),
                startedAt.plusSeconds(47)
            )
        ).isTrue()

        val renewedExpiresAt = startedAt.plusSeconds(60)
        assertThat(
            lockRepository.renewLock(
                logId,
                secondLock,
                startedAt.plusSeconds(3),
                renewedExpiresAt
            )
        ).isTrue()

        val renewed = logRepository.findById(logId).orElseThrow()
        assertThat(renewed.aiReviewLockVersion).isEqualTo(secondLock.version)
        assertThat(renewed.aiReviewLockExpiresAt).isEqualTo(renewedExpiresAt)
    }

    @Test
    fun `만료 시각과 같은 순간에는 종료할 수 없고 새 잠금은 획득할 수 있다`() {
        val logId = saveLog()
        val startedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
        val expiresAt = startedAt.plusSeconds(1)
        val lock = requireNotNull(
            lockRepository.tryAcquireLock(logId, startedAt, expiresAt)
        )

        assertThat(
            lockRepository.markCompleted(
                logId,
                lock,
                expiresAt,
                "expired-review",
                100L
            )
        ).isFalse()
        assertThat(
            lockRepository.markFailed(logId, lock, expiresAt)
        ).isFalse()
        assertThat(
            lockRepository.renewLock(
                logId,
                lock,
                expiresAt,
                expiresAt.plusSeconds(45)
            )
        ).isFalse()

        val unchanged = logRepository.findById(logId).orElseThrow()
        assertThat(unchanged.aiReview).isNull()
        assertThat(unchanged.aiReviewStatus).isEqualTo(AiReviewStatus.IN_PROGRESS)
        assertThat(unchanged.aiReviewLockExpiresAt).isEqualTo(expiresAt)
        assertThat(unchanged.aiReviewLockVersion).isEqualTo(lock.version)

        val nextLock = lockRepository.tryAcquireLock(
            logId,
            expiresAt,
            expiresAt.plusSeconds(45)
        )
        assertThat(nextLock).isNotNull
        assertThat(requireNotNull(nextLock).version).isGreaterThan(lock.version)
    }

    @Test
    fun `잠금 버전 필드가 없는 기존 문서도 첫 획득에서 버전 1을 받는다`() {
        val logId = "legacy-lock-log"
        mongoTemplate.getCollection("logs").insertOne(
            Document("_id", logId)
                .append("title", "기존 로그")
                .append("content", "잠금 버전 필드 없음")
                .append("code", "fun main() = println(\"legacy\")")
        )
        val startedAt = LocalDateTime.of(2026, 1, 1, 0, 0)

        val lock = lockRepository.tryAcquireLock(
            logId,
            startedAt,
            startedAt.plusSeconds(45)
        )

        assertThat(requireNotNull(lock).version).isEqualTo(1L)
        val stored = mongoTemplate.getCollection("logs")
            .find(Document("_id", logId))
            .first()
        assertThat(
            (requireNotNull(stored)["aiReviewLockVersion"] as Number).toLong()
        ).isEqualTo(1L)
    }

    private fun saveLog(): String {
        return requireNotNull(
            logRepository.save(
                Log(
                    title = LogTitle("AI 리뷰 잠금"),
                    content = LogContent("잠금 소유권 검증"),
                    code = LogCode("fun main() = println(\"lock\")")
                )
            ).id
        )
    }

    companion object {
        private val databaseName =
            "didimlog-ai-lock-${UUID.randomUUID().toString().replace("-", "")}"

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
