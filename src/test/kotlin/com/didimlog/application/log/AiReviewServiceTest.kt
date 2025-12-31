package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.infra.ai.AiApiClient
import com.didimlog.infra.ai.AiApiResponse
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

@DisplayName("AiReviewService 테스트")
class AiReviewServiceTest {

    private val logRepository: LogRepository = mockk()
    private val aiApiClient: AiApiClient = mockk()
    private val lockRepository: LogAiReviewLockRepository = mockk()
    private val aiReviewService = AiReviewService(logRepository, aiApiClient, lockRepository)

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
        every { lockRepository.markCompleted(any(), any()) } returns true
        every { lockRepository.markFailed(any()) } returns true
        every { aiApiClient.requestOneLineReview(any()) } answers {
            val prompt = firstArg<String>()
            assertThat(prompt).contains("a".repeat(2_000))
            assertThat(prompt).doesNotContain("a".repeat(2_001))
            AiApiResponse(rawJson = """{"review":"ok"}""", review = "ok")
        }

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("ok")
        assertThat(result.cached).isFalse()
        verify(exactly = 1) { aiApiClient.requestOneLineReview(any()) }
        verify(exactly = 1) { lockRepository.markCompleted(logId, "ok") }
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
            aiReview = null
        )
        every { logRepository.findById(logId) } returns Optional.of(log)

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).isEqualTo("Code is too short to analyze")
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
            aiReview = null
        )
        every { logRepository.findById(logId) } returns Optional.of(log)
        every { lockRepository.tryAcquireLock(any(), any(), any()) } returns false
        every { lockRepository.isInProgress(any(), any()) } returns true

        val result = aiReviewService.requestOneLineReview(logId)

        assertThat(result.review).contains("AI review is being generated")
        assertThat(result.cached).isFalse()
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) { lockRepository.markCompleted(any(), any()) }
        verify(exactly = 0) { lockRepository.markFailed(any()) }
    }
}


