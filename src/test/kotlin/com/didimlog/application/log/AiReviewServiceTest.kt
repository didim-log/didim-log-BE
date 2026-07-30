package com.didimlog.application.log

import com.didimlog.application.ai.AiUsageService
import com.didimlog.domain.Log
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.ai.AiApiClient
import com.didimlog.infra.ai.AiApiResponse
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.task.TaskExecutor
import java.util.Optional
import java.util.concurrent.RejectedExecutionException

@DisplayName("AiReviewService 테스트")
class AiReviewServiceTest {

    private val logRepository: LogRepository = mockk()
    private val aiApiClient: AiApiClient = mockk()
    private val lockRepository: LogAiReviewLockRepository = mockk()
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns true
        every { lockRepository.markCompleted(any(), any(), any()) } returns true
        every { lockRepository.markFailed(any()) } returns true
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
        verify(exactly = 1) { lockRepository.markCompleted(logId, "ok", any()) }
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns false
        every { lockRepository.isInProgress(any(), any()) } returns true

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요.")
        assertThat(result.cached).isFalse()
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) { lockRepository.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { lockRepository.markFailed(any()) }
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns true
        every { lockRepository.markCompleted(any(), any(), any()) } returns true
        every { aiApiClient.requestOneLineReview(any(), any()) } returns AiApiResponse(
            rawJson = """{"review":"ok"}""",
            review = "ok"
        )
        val result = aiReviewService.requestOneLineReviewAsync(logId, "user123")

        assertThat(result.inProgress).isTrue()
        assertThat(result.cached).isFalse()
        verify(exactly = 1) { aiApiClient.requestOneLineReview(any(), any()) }
        verify(exactly = 1) { lockRepository.markCompleted(logId, "ok", any()) }
        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 0) { aiUsageService.releaseUsage(any()) }
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns true
        every { lockRepository.markCompleted(any(), any(), any()) } throws
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
        verify(exactly = 1) { lockRepository.markCompleted(logId, "ok", any()) }
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns true
        every { lockRepository.markFailed(logId) } returns true
        every { aiApiClient.requestOneLineReview(any(), any()) } throws
            IllegalStateException("provider unavailable")

        assertThatThrownBy { aiReviewService.requestOneLineReview(logId) }
            .isInstanceOf(AiGenerationFailedException::class.java)

        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 1) { lockRepository.markFailed(logId) }
        verify(exactly = 0) { lockRepository.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { aiReviewCodeCacheService.cacheReview(any(), any(), any()) }
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns true
        every { lockRepository.markFailed(logId) } returns true

        assertThatThrownBy {
            service.requestOneLineReviewAsync(logId, "user123")
        }
            .isInstanceOf(AiGenerationFailedException::class.java)
            .hasMessageContaining("작업 등록")

        verify(exactly = 1) { aiUsageService.reserveUsage("user123") }
        verify(exactly = 1) { aiUsageService.releaseUsage(usageReservation) }
        verify(exactly = 1) { lockRepository.markFailed(logId) }
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
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns true
        every { lockRepository.markFailed(logId) } returns true
        every { aiUsageService.reserveUsage("user123") } throws BusinessException(
            ErrorCode.AI_USER_LIMIT_EXCEEDED
        )

        assertThatThrownBy {
            aiReviewService.requestOneLineReviewAsync(logId, "user123")
        }
            .isInstanceOf(BusinessException::class.java)
            .matches { (it as BusinessException).errorCode == ErrorCode.AI_USER_LIMIT_EXCEEDED }

        verify(exactly = 1) { lockRepository.markFailed(logId) }
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
        verify(exactly = 0) { lockRepository.markFailed(any()) }
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
}
