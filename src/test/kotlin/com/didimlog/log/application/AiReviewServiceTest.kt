package com.didimlog.log.application

import com.didimlog.ai.application.AiUsageService
import com.didimlog.log.domain.Log
import com.didimlog.log.domain.AiReviewStatus
import com.didimlog.log.domain.repository.LogRepository
import com.didimlog.log.domain.AiReview
import com.didimlog.log.domain.LogCode
import com.didimlog.log.domain.LogContent
import com.didimlog.log.domain.LogTitle
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ai.infra.AiApiClient
import com.didimlog.ai.infra.AiApiResponse
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.task.TaskExecutor
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.RejectedExecutionException

@DisplayName("AiReviewService 테스트")
class AiReviewServiceTest {

    private val logRepository: LogRepository = mockk()
    private val aiApiClient: AiApiClient = mockk()
    private val lockRepository: LogAiReviewLockRepository = mockk()
    private val acquiredLock = AiReviewLock(1L)
    private val aiUsageService: AiUsageService = mockk(relaxed = true)
    private val usageReservation: AiUsageService.UsageReservation = mockk()
    private val aiReviewCodeCacheService: AiReviewCodeCacheService = mockk(relaxed = true)
    private val aiReviewTaskExecutor: TaskExecutor = TaskExecutor { task -> task.run() }
    private val aiReviewService = AiReviewService(
        logRepository,
        aiApiClient,
        lockRepository,
        aiUsageService,
        aiReviewCodeCacheService,
        aiReviewTaskExecutor
    )

    @BeforeEach
    fun setUp() {
        every { aiReviewCodeCacheService.getCachedReview(any(), any()) } returns null
        every { aiReviewCodeCacheService.cacheReview(any(), any(), any()) } returns Unit
        every { aiUsageService.reserveUsage(any()) } returns usageReservation
        every { aiUsageService.releaseUsage(any()) } returns true
        every {
            lockRepository.renewLock(any(), any(), any(), any())
        } returns true
    }

    @Test
    @DisplayName("이미 aiReview가 있으면 외부 API를 호출하지 않고 캐시를 반환한다")
    fun `cache first`() {
        val logId = "log-1"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("code"),
            aiReview = AiReview("cached")
        )
        every { logRepository.findById(logId) } returns Optional.of(log)

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("cached")
        assertThat(result.cached).isTrue()
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("동일 코드 캐시가 있으면 외부 API를 호출하지 않고 결과를 반환한다")
    fun `code hash cache first`() {
        val logId = "log-cache-1"
        val code = "public class Main { public static void main(String[] args) { System.out.println(1); } }"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode(code),
            aiReview = null
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every { aiReviewCodeCacheService.getCachedReview(code.trim(), null) } returns "code-cached"

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("code-cached")
        assertThat(result.cached).isTrue()
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) { lockRepository.tryAcquireLock(any(), any(), any()) }
    }

    @Test
    @DisplayName("코드가 2000자를 초과하면 프롬프트에 2000자까지만 포함한다")
    fun `truncate code`() {
        val logId = "log-2"
        val longCode = "a".repeat(2_500)
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode(longCode),
            aiReview = null
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        } returns true
        every { lockRepository.markFailed(any(), any(), any()) } returns true
        every { aiApiClient.requestOneLineReview(any(), any()) } answers {
            val prompt = firstArg<String>()
            assertThat(prompt).contains("a".repeat(2_000))
            assertThat(prompt).doesNotContain("a".repeat(2_001))
            AiApiResponse(rawJson = """{"review":"ok"}""", review = "ok")
        }

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("ok")
        assertThat(result.cached).isFalse()
        verify(exactly = 1) { aiApiClient.requestOneLineReview(any(), any()) }
        verify(exactly = 1) {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "ok",
                any()
            )
        }
        verify(exactly = 1) { aiReviewCodeCacheService.cacheReview(any(), null, "ok") }
    }

    @Test
    @DisplayName("코드가 너무 짧으면 외부 API를 호출하지 않고 기본 메시지를 반환한다")
    fun `code too short`() {
        val logId = "log-3"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("short"),
            aiReview = null,
            bojId = null // bojId가 없으면 사용량 체크를 하지 않음
        )
        every { logRepository.findById(logId) } returns Optional.of(log)

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("코드가 너무 짧아 분석할 수 없습니다")
        assertThat(result.cached).isFalse()
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("락을 획득하지 못하면 in-progress 메시지를 반환하고 외부 API를 호출하지 않는다")
    fun `in progress`() {
        val logId = "log-4"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("0123456789"),
            studentId = "user123",
            aiReview = null,
            bojId = null
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns null
        every { lockRepository.isInProgress(any(), any()) } returns true

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요.")
        assertThat(result.cached).isFalse()
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { lockRepository.markFailed(any(), any(), any()) }
        verify(exactly = 0) { aiUsageService.reserveUsage(any()) }
    }

    @Test
    @DisplayName("비동기 요청은 202 상태용 inProgress 결과를 즉시 반환한다")
    fun `async request returns in progress`() {
        val logId = "log-5"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("0123456789"),
            studentId = "user123",
            aiReview = null,
            bojId = null
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        } returns true
        every { aiApiClient.requestOneLineReview(any(), any()) } returns AiApiResponse(
            rawJson = """{"review":"ok"}""",
            review = "ok"
        )
        val result = aiReviewService.requestOneLineReviewAsync(logId, "user123")

        assertThat(result.inProgress).isTrue()
        assertThat(result.cached).isFalse()
        verify(exactly = 1) { aiApiClient.requestOneLineReview(any(), any()) }
        verify(exactly = 1) {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "ok",
                any()
            )
        }
        verify(exactly = 1) {
            lockRepository.renewLock(logId, acquiredLock, any(), any())
        }
        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
    }

    @Test
    @DisplayName("비동기 실행 전에 잠금을 잃으면 사용량을 반환하고 공급자를 호출하지 않는다")
    fun `lost async lock releases reservation before provider call`() {
        val logId = "log-lost-before-execution"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.renewLock(logId, acquiredLock, any(), any())
        } returns false

        val result = aiReviewService.requestOneLineReviewAsync(
            logId,
            "user123"
        )

        assertThat(result.inProgress).isTrue()
        verify(exactly = 1) {
            aiUsageService.releaseUsage(usageReservation)
        }
        verify(exactly = 0) {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { lockRepository.markFailed(any(), any(), any()) }
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) {
            aiReviewCodeCacheService.cacheReview(any(), any(), any())
        }
    }

    @Test
    @DisplayName("비동기 잠금 갱신 중 저장소 장애가 나면 사용량을 반환하고 공급자를 호출하지 않는다")
    fun `async lock renewal error releases reservation before provider call`() {
        val logId = "log-renewal-error"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.renewLock(logId, acquiredLock, any(), any())
        } throws IllegalStateException("mongo unavailable")

        val result = aiReviewService.requestOneLineReviewAsync(
            logId,
            "user123"
        )

        assertThat(result.inProgress).isTrue()
        verify(exactly = 1) {
            aiUsageService.releaseUsage(usageReservation)
        }
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            lockRepository.markFailed(any(), any(), any())
        }
    }

    @Test
    @DisplayName("AI 공급자 성공 뒤 저장이 실패해도 사용량 예약은 유지한다")
    fun `save failure after provider success keeps usage reservation`() {
        val logId = "log-save-failure"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        } throws
            IllegalStateException("save failed")
        every { aiApiClient.requestOneLineReview(any(), any()) } returns AiApiResponse(
            rawJson = """{"review":"ok"}""",
            review = "ok"
        )

        assertThatThrownBy { aiReviewService.requestOneLineReview(logId) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("save failed")

        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
        verify(exactly = 1) {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "ok",
                any()
            )
        }
    }

    @Test
    @DisplayName("완료 전에 잠금을 잃으면 생성 결과를 반환하거나 캐시하지 않는다")
    fun `lost completion lock does not expose stale review`() {
        val logId = "log-lost-before-completion"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        val successorLog = log.copy(
            aiReviewStatus = AiReviewStatus.IN_PROGRESS,
            aiReviewLockExpiresAt = LocalDateTime.now().plusMinutes(1),
            aiReviewLockVersion = acquiredLock.version + 1
        )
        every {
            logRepository.findById(logId)
        } returnsMany listOf(
            Optional.of(log),
            Optional.of(successorLog)
        )
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "stale-review",
                any()
            )
        } returns false
        every {
            aiApiClient.requestOneLineReview(any(), any())
        } returns AiApiResponse(
            rawJson = """{"review":"stale-review"}""",
            review = "stale-review"
        )

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.inProgress).isTrue()
        assertThat(result.review)
            .isEqualTo("AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요.")
        verify(exactly = 0) {
            aiReviewCodeCacheService.cacheReview(any(), any(), any())
        }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
    }

    @Test
    @DisplayName("완료 저장이 거절되고 후속 작업이 없으면 재시도 가능한 실패를 반환한다")
    fun `lost completion without active successor returns failure`() {
        val logId = "log-lost-without-successor"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "orphan-review",
                any()
            )
        } returns false
        every {
            aiApiClient.requestOneLineReview(any(), any())
        } returns AiApiResponse(
            rawJson = """{"review":"orphan-review"}""",
            review = "orphan-review"
        )

        assertThatThrownBy {
            aiReviewService.requestOneLineReview(logId)
        }
            .isInstanceOf(AiGenerationFailedException::class.java)
            .hasMessage("AI 리뷰 결과를 저장할 수 없습니다. 다시 시도해주세요.")

        verify(exactly = 0) {
            aiReviewCodeCacheService.cacheReview(any(), any(), any())
        }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["same-version", "expired-successor"])
    @DisplayName("현재 잠금이 아닌 유효한 후속 작업만 생성 중으로 판단한다")
    fun `invalid successor snapshot returns failure`(scenario: String) {
        val logId = "log-invalid-successor-$scenario"
        val initialLog = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        val now = LocalDateTime.now()
        val invalidSuccessor = when (scenario) {
            "same-version" -> initialLog.copy(
                aiReviewStatus = AiReviewStatus.IN_PROGRESS,
                aiReviewLockExpiresAt = now.plusMinutes(1),
                aiReviewLockVersion = acquiredLock.version
            )
            "expired-successor" -> initialLog.copy(
                aiReviewStatus = AiReviewStatus.IN_PROGRESS,
                aiReviewLockExpiresAt = now.minusSeconds(1),
                aiReviewLockVersion = acquiredLock.version + 1
            )
            else -> error("지원하지 않는 시나리오: $scenario")
        }
        every {
            logRepository.findById(logId)
        } returnsMany listOf(
            Optional.of(initialLog),
            Optional.of(invalidSuccessor)
        )
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "previous-review",
                any()
            )
        } returns false
        every {
            aiApiClient.requestOneLineReview(any(), any())
        } returns AiApiResponse(
            rawJson = """{"review":"previous-review"}""",
            review = "previous-review"
        )

        assertThatThrownBy {
            aiReviewService.requestOneLineReview(logId)
        }
            .isInstanceOf(AiGenerationFailedException::class.java)
            .hasMessage("AI 리뷰 결과를 저장할 수 없습니다. 다시 시도해주세요.")

        verify(exactly = 0) {
            aiReviewCodeCacheService.cacheReview(any(), any(), any())
        }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
    }

    @Test
    @DisplayName("완료 저장 경합 중 후속 작업이 끝나면 후속 작업의 리뷰를 반환한다")
    fun `concurrent completion returns current stored review`() {
        val logId = "log-concurrent-completion"
        val initialLog = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        val completedLog = initialLog.copy(aiReview = AiReview("current-review"))
        every {
            logRepository.findById(logId)
        } returnsMany listOf(
            Optional.of(initialLog),
            Optional.of(completedLog)
        )
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markCompleted(
                logId,
                acquiredLock,
                any(),
                "previous-review",
                any()
            )
        } returns false
        every {
            aiApiClient.requestOneLineReview(any(), any())
        } returns AiApiResponse(
            rawJson = """{"review":"previous-review"}""",
            review = "previous-review"
        )

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("current-review")
        assertThat(result.cached).isTrue()
        verify(exactly = 0) {
            aiReviewCodeCacheService.cacheReview(any(), any(), any())
        }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
    }

    @Test
    @DisplayName("AI 공급자 호출이 실패하면 사용량 예약을 반환한다")
    fun `provider failure releases usage reservation`() {
        val logId = "log-provider-failure"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markFailed(logId, acquiredLock, any())
        } returns true
        every { aiApiClient.requestOneLineReview(any(), any()) } throws
            IllegalStateException("provider unavailable")

        assertThatThrownBy { aiReviewService.requestOneLineReview(logId) }
            .isInstanceOf(AiGenerationFailedException::class.java)

        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 1) {
            lockRepository.markFailed(logId, acquiredLock, any())
        }
        verify(exactly = 0) {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { aiReviewCodeCacheService.cacheReview(any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(
        value = ErrorCode::class,
        names = [
            "AI_CONTEXT_TOO_LARGE",
            "AI_SERVICE_BUSY",
            "RATE_LIMIT_SERVICE_UNAVAILABLE"
        ]
    )
    @DisplayName("전달 대상으로 정한 AI 예외는 상태 코드를 유지하고 사용량을 반환한다")
    fun `selected ai errors preserve business exception`(errorCode: ErrorCode) {
        val logId = "log-provider-rate-limit"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        val failure = BusinessException(errorCode)
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markFailed(logId, acquiredLock, any())
        } returns true
        every {
            aiApiClient.requestOneLineReview(any(), any())
        } throws failure

        assertThatThrownBy {
            aiReviewService.requestOneLineReview(logId)
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { it === failure }

        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 1) {
            lockRepository.markFailed(logId, acquiredLock, any())
        }
        verify(exactly = 0) {
            lockRepository.markCompleted(any(), any(), any(), any(), any())
        }
    }

    @Test
    @DisplayName("AI 공급자의 내부 오류는 기존 생성 실패 응답으로 변환한다")
    fun `provider internal business error maps to generation failure`() {
        val logId = "log-provider-internal-error"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markFailed(logId, acquiredLock, any())
        } returns true
        every {
            aiApiClient.requestOneLineReview(any(), any())
        } throws BusinessException(ErrorCode.COMMON_INTERNAL_ERROR)

        assertThatThrownBy {
            aiReviewService.requestOneLineReview(logId)
        }.isInstanceOf(AiGenerationFailedException::class.java)

        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 1) {
            lockRepository.markFailed(logId, acquiredLock, any())
        }
    }

    @Test
    @DisplayName("비동기 작업 등록이 거절되면 사용량 예약을 반환한다")
    fun `executor rejection releases usage reservation`() {
        val logId = "log-executor-rejection"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        val rejectingExecutor = TaskExecutor {
            throw RejectedExecutionException("queue full")
        }
        val service = AiReviewService(
            logRepository,
            aiApiClient,
            lockRepository,
            aiUsageService,
            aiReviewCodeCacheService,
            rejectingExecutor
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markFailed(logId, acquiredLock, any())
        } returns true

        assertThatThrownBy {
            service.requestOneLineReviewAsync(logId, "user123")
        }
            .isInstanceOf(AiGenerationFailedException::class.java)
            .hasMessageContaining("작업 등록")

        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 1) {
            lockRepository.markFailed(logId, acquiredLock, any())
        }
        verify { aiApiClient wasNot Called }
    }

    @Test
    @DisplayName("사용량 예약이 거절되면 획득한 로그 락을 실패 처리한다")
    fun `quota rejection marks acquired lock as failed`() {
        val logId = "log-quota-rejection"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("public class Main { public static void main(String[] args) { } }"),
            studentId = "user123"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every {
            lockRepository.tryAcquireLock(any(), any(), any())
        } returns acquiredLock
        every {
            lockRepository.markFailed(logId, acquiredLock, any())
        } returns true
        every { aiUsageService.reserveUsage("user123") } throws BusinessException(
            ErrorCode.AI_USER_LIMIT_EXCEEDED
        )

        assertThatThrownBy {
            aiReviewService.requestOneLineReviewAsync(logId, "user123")
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.AI_USER_LIMIT_EXCEEDED }

        verify(exactly = 1) {
            lockRepository.markFailed(logId, acquiredLock, any())
        }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
        verify { aiApiClient wasNot Called }
    }

    @Test
    @DisplayName("비동기 요청 시 다른 사용자의 로그를 요청하면 접근 거부한다")
    fun `async request forbidden for different owner`() {
        val logId = "log-6"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("0123456789"),
            studentId = "owner-id",
            aiReview = null,
            bojId = com.didimlog.domain.valueobject.BojId("owner123")
        )
        every { logRepository.findById(logId) } returns Optional.of(log)

        assertThatThrownBy {
            aiReviewService.requestOneLineReviewAsync(logId, "attacker")
        }
            .isInstanceOf(com.didimlog.global.exception.BusinessException::class.java)
            .hasMessageContaining("본인이 작성한 로그")

        verify(exactly = 0) { lockRepository.tryAcquireLock(any(), any(), any()) }
        verify(exactly = 0) { lockRepository.markFailed(any(), any(), any()) }
        verify { aiApiClient wasNot Called }
    }

    @Test
    @DisplayName("다른 학생은 로그에 저장된 AI 리뷰 캐시를 조회할 수 없다")
    fun `entity cache is denied before return for different owner`() {
        val logId = "log-cached-owner"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("0123456789"),
            studentId = "owner-id",
            aiReview = AiReview("cached")
        )
        every { logRepository.findById(logId) } returns Optional.of(log)

        assertThatThrownBy {
            aiReviewService.requestOneLineReviewAsync(logId, "attacker-id")
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.ACCESS_DENIED }

        verify(exactly = 0) { aiReviewCodeCacheService.getCachedReview(any(), any()) }
        verify(exactly = 0) { lockRepository.tryAcquireLock(any(), any(), any()) }
        verify { aiApiClient wasNot Called }
    }

    @Test
    @DisplayName("다른 학생은 코드 해시 AI 리뷰 캐시를 조회할 수 없다")
    fun `code cache is denied before lookup for different owner`() {
        val logId = "log-code-cached-owner"
        val code = "public class Main { public static void main(String[] args) { } }"
        val log = Log(
            id = logId,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode(code),
            studentId = "owner-id"
        )
        every { logRepository.findById(logId) } returns Optional.of(log)

        assertThatThrownBy {
            aiReviewService.requestOneLineReviewAsync(logId, "attacker-id")
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.ACCESS_DENIED }

        verify(exactly = 0) { aiReviewCodeCacheService.getCachedReview(any(), any()) }
        verify(exactly = 0) { lockRepository.tryAcquireLock(any(), any(), any()) }
        verify { aiApiClient wasNot Called }
    }

    private fun reviewableLog(
        logId: String,
        code: String = "public class Main { public static void main(String[] args) { System.out.println(1); } }",
        isSuccess: Boolean? = null,
        studentId: String? = "user123",
        aiReview: AiReview? = null
    ) = Log(
        id = logId,
        title = LogTitle("제목"),
        content = LogContent("내용"),
        code = LogCode(code),
        studentId = studentId,
        isSuccess = isSuccess,
        aiReview = aiReview
    )

    private fun stubLockLifecycle() {
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns acquiredLock
        every { lockRepository.markCompleted(any(), any(), any(), any(), any()) } returns true
        every { lockRepository.markFailed(any(), any(), any()) } returns true
    }

    private fun capturePrompt(logId: String, log: Log): String {
        every { logRepository.findById(logId) } returns Optional.of(log)
        stubLockLifecycle()
        var prompt = ""
        every { aiApiClient.requestOneLineReview(any(), any()) } answers {
            prompt = firstArg()
            AiApiResponse(rawJson = """{"review":"ok"}""", review = "ok")
        }
        aiReviewService.requestOneLineReview(logId)
        return prompt
    }

    @Test
    @DisplayName("성공한 풀이의 프롬프트는 품질 개선에 초점을 맞추고 감지한 언어 태그를 지정한다")
    fun `success prompt uses detected language tag`() {
        val prompt = capturePrompt(
            "log-prompt-success",
            reviewableLog(
                "log-prompt-success",
                code = "def solve():\n    return sum(range(10))\nprint(solve())",
                isSuccess = true
            )
        )

        assertThat(prompt).startsWith("이 코드는 성공적으로 실행되었습니다. 이 PYTHON 코드를 분석하고")
        assertThat(prompt).contains("코드 품질 향상")
        assertThat(prompt).contains("```python")
    }

    @Test
    @DisplayName("실패한 풀이의 프롬프트는 실패 원인 분석을 요청한다")
    fun `failure prompt asks for failure analysis`() {
        val prompt = capturePrompt("log-prompt-fail", reviewableLog("log-prompt-fail", isSuccess = false))

        assertThat(prompt).startsWith("이 코드는 실행에 실패했습니다. 이 JAVA 코드를 분석하고")
        assertThat(prompt).contains("실패 원인 분석")
        assertThat(prompt).contains("```java")
    }

    @Test
    @DisplayName("결과를 모르면 결과 문맥 없이 일반 리뷰를 요청하고 감지 불가 언어는 소문자 태그로 지정한다")
    fun `unknown result prompt has no result context`() {
        val prompt = capturePrompt(
            "log-prompt-unknown",
            reviewableLog("log-prompt-unknown", code = "그냥 설명만 있는 긴 텍스트입니다", isSuccess = null)
        )

        assertThat(prompt).startsWith("이 TEXT 코드를 분석하고")
        assertThat(prompt).contains("클린 코드 원칙")
        assertThat(prompt).contains("```text")
    }

    @Test
    @DisplayName("AI 호출 시간 초과는 시간 초과 예외로 변환하고 잠금을 실패 처리한다")
    fun `provider timeout maps to timeout exception`() {
        every { logRepository.findById("log-timeout") } returns Optional.of(reviewableLog("log-timeout"))
        stubLockLifecycle()
        every { aiApiClient.requestOneLineReview(any(), any()) } throws java.util.concurrent.TimeoutException("slow")

        assertThatThrownBy { aiReviewService.requestOneLineReview("log-timeout") }
            .isInstanceOf(com.didimlog.global.exception.AiGenerationTimeoutException::class.java)
        verify(exactly = 1) { lockRepository.markFailed("log-timeout", acquiredLock, any()) }
        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 0) { aiUsageService.emergencyStop() }
    }

    @Test
    @DisplayName("AI 제공자가 429를 반환하면 AI 서비스를 긴급 중지한다")
    fun `provider 429 triggers emergency stop`() {
        every { logRepository.findById("log-429") } returns Optional.of(reviewableLog("log-429"))
        stubLockLifecycle()
        every { aiApiClient.requestOneLineReview(any(), any()) } throws
            org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)

        assertThatThrownBy { aiReviewService.requestOneLineReview("log-429") }
            .isInstanceOf(AiGenerationFailedException::class.java)
        verify(exactly = 1) { aiUsageService.emergencyStop() }
    }

    @Test
    @DisplayName("429가 아닌 클라이언트 오류는 긴급 중지하지 않는다")
    fun `provider 400 does not trigger emergency stop`() {
        every { logRepository.findById("log-400") } returns Optional.of(reviewableLog("log-400"))
        stubLockLifecycle()
        every { aiApiClient.requestOneLineReview(any(), any()) } throws
            org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.BAD_REQUEST)

        assertThatThrownBy { aiReviewService.requestOneLineReview("log-400") }
            .isInstanceOf(AiGenerationFailedException::class.java)
        verify(exactly = 0) { aiUsageService.emergencyStop() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["QuotaExceeded for model", "upstream returned 429"])
    @DisplayName("기타 예외라도 Quota 초과 메시지면 AI 서비스를 긴급 중지한다")
    fun `quota message triggers emergency stop`(message: String) {
        every { logRepository.findById("log-quota") } returns Optional.of(reviewableLog("log-quota"))
        stubLockLifecycle()
        every { aiApiClient.requestOneLineReview(any(), any()) } throws IllegalStateException(message)

        assertThatThrownBy { aiReviewService.requestOneLineReview("log-quota") }
            .isInstanceOf(AiGenerationFailedException::class.java)
            .hasCauseInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { aiUsageService.emergencyStop() }
    }

    @Test
    @DisplayName("Quota와 무관한 기타 예외는 긴급 중지 없이 생성 실패로 변환한다")
    fun `generic provider error does not trigger emergency stop`() {
        every { logRepository.findById("log-generic") } returns Optional.of(reviewableLog("log-generic"))
        stubLockLifecycle()
        every { aiApiClient.requestOneLineReview(any(), any()) } throws IllegalStateException("connection reset")

        assertThatThrownBy { aiReviewService.requestOneLineReview("log-generic") }
            .isInstanceOf(AiGenerationFailedException::class.java)
        verify(exactly = 0) { aiUsageService.emergencyStop() }
    }

    @Test
    @DisplayName("예약 해제 중 예외가 나도 원래 AI 실패 예외를 전파한다")
    fun `release failure does not hide provider failure`() {
        every { logRepository.findById("log-release") } returns Optional.of(reviewableLog("log-release"))
        stubLockLifecycle()
        every { aiUsageService.releaseUsage(any()) } throws IllegalStateException("redis down")
        every { aiApiClient.requestOneLineReview(any(), any()) } throws IllegalStateException("provider down")

        assertThatThrownBy { aiReviewService.requestOneLineReview("log-release") }
            .isInstanceOf(AiGenerationFailedException::class.java)
            .hasRootCauseMessage("provider down")
    }

    @Test
    @DisplayName("잠금 획득에 실패했지만 그사이 리뷰가 저장되었으면 저장된 리뷰를 반환한다")
    fun `lock not acquired returns review saved meanwhile`() {
        every { logRepository.findById("log-race") } returnsMany listOf(
            Optional.of(reviewableLog("log-race")),
            Optional.of(reviewableLog("log-race", aiReview = AiReview("방금 저장된 리뷰")))
        )
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns null

        val result = aiReviewService.requestOneLineReview("log-race")

        assertThat(result.review).isEqualTo("방금 저장된 리뷰")
        assertThat(result.cached).isTrue()
        verify { aiApiClient wasNot Called }
    }

    @Test
    @DisplayName("잠금 획득에 실패했지만 그사이 같은 코드 캐시가 생겼으면 캐시를 반환한다")
    fun `lock not acquired returns code cache filled meanwhile`() {
        val log = reviewableLog("log-race-cache")
        every { logRepository.findById("log-race-cache") } returns Optional.of(log)
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns null
        every { aiReviewCodeCacheService.getCachedReview(log.code.value, null) } returnsMany listOf(null, "캐시 리뷰")

        val result = aiReviewService.requestOneLineReview("log-race-cache")

        assertThat(result.review).isEqualTo("캐시 리뷰")
        assertThat(result.cached).isTrue()
    }

    @Test
    @DisplayName("작성자가 없는 로그는 사용량 예약 없이 리뷰를 생성한다")
    fun `anonymous log skips usage reservation`() {
        every { logRepository.findById("log-anon") } returns Optional.of(reviewableLog("log-anon", studentId = null))
        stubLockLifecycle()
        every { aiApiClient.requestOneLineReview(any(), any()) } returns AiApiResponse(rawJson = "{}", review = "ok")

        val result = aiReviewService.requestOneLineReview("log-anon")

        assertThat(result.review).isEqualTo("ok")
        verify(exactly = 0) { aiUsageService.reserveUsage(any()) }
        verify(exactly = 1) { aiReviewCodeCacheService.cacheReview(any(), null, "ok") }
    }

    @Test
    @DisplayName("로그가 없으면 리뷰를 요청할 수 없다")
    fun `missing log`() {
        every { logRepository.findById("missing") } returns Optional.empty()

        assertThatThrownBy { aiReviewService.requestOneLineReview("missing") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("로그를 찾을 수 없습니다. logId=missing")
    }

    @Test
    @DisplayName("비동기 요청은 인증되지 않은 요청을 거부한다")
    fun `async request requires requester`() {
        every { logRepository.findById("log-auth") } returns Optional.of(reviewableLog("log-auth"))

        assertThatThrownBy { aiReviewService.requestOneLineReviewAsync("log-auth", " ") }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED)
    }

    @Test
    @DisplayName("비동기 요청도 저장된 리뷰, 짧은 코드, 코드 캐시를 작업 등록 전에 처리한다")
    fun `async request short circuits before scheduling`() {
        every { logRepository.findById("log-saved") } returns
            Optional.of(reviewableLog("log-saved", aiReview = AiReview("저장된 리뷰")))
        every { logRepository.findById("log-short") } returns Optional.of(reviewableLog("log-short", code = "x = 1"))
        val cachedLog = reviewableLog("log-cached")
        every { logRepository.findById("log-cached") } returns Optional.of(cachedLog)
        every { aiReviewCodeCacheService.getCachedReview(cachedLog.code.value, null) } returns "코드 캐시"

        val saved = aiReviewService.requestOneLineReviewAsync("log-saved", "user123")
        val short = aiReviewService.requestOneLineReviewAsync("log-short", "user123")
        val cached = aiReviewService.requestOneLineReviewAsync("log-cached", "user123")

        assertThat(saved).isEqualTo(AiReviewResult(review = "저장된 리뷰", cached = true))
        assertThat(short).isEqualTo(AiReviewResult(review = "코드가 너무 짧아 분석할 수 없습니다", cached = false))
        assertThat(cached).isEqualTo(AiReviewResult(review = "코드 캐시", cached = true))
        verify(exactly = 0) { lockRepository.tryAcquireLock(any(), any(), any()) }
        verify { aiApiClient wasNot Called }
    }

    @Test
    @DisplayName("비동기 요청에서 잠금을 얻지 못하면 진행 중 결과를 반환한다")
    fun `async request without lock returns in progress`() {
        every { logRepository.findById("log-busy") } returns Optional.of(reviewableLog("log-busy"))
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns null

        val result = aiReviewService.requestOneLineReviewAsync("log-busy", "user123")

        assertThat(result.inProgress).isTrue()
        verify(exactly = 0) { aiUsageService.reserveUsage(any()) }
    }
}
