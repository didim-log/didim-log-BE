package com.didimlog.log.application

import com.didimlog.ai.application.AiUsageService
import com.didimlog.ai.infra.AiApiClient
import com.didimlog.log.domain.AiReview
import com.didimlog.log.domain.Log
import com.didimlog.log.domain.LogCode
import com.didimlog.log.domain.LogContent
import com.didimlog.log.domain.LogTitle
import com.didimlog.log.domain.repository.LogRepository
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.task.TaskExecutor
import java.util.Optional

@DisplayName("AiReviewService 즉시 응답 조건 테스트")
class AiReviewImmediateResultTest {

    private val logRepository: LogRepository = mockk()
    private val aiApiClient: AiApiClient = mockk()
    private val lockRepository: LogAiReviewLockRepository = mockk()
    private val aiUsageService: AiUsageService = mockk(relaxed = true)
    private val aiReviewCodeCacheService: AiReviewCodeCacheService = mockk(relaxed = true)
    private val aiReviewTaskExecutor: TaskExecutor = mockk(relaxed = true)
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
    }

    @Test
    @DisplayName("비동기 요청도 로그에 저장된 리뷰가 있으면 코드 캐시와 잠금 없이 반환한다")
    fun `async entity cache`() {
        givenLog(code = "0123456789", aiReview = AiReview("stored review"))

        val result = aiReviewService.requestOneLineReviewAsync(LOG_ID, OWNER_ID)

        assertThat(result.review).isEqualTo("stored review")
        assertThat(result.cached).isTrue()
        assertThat(result.inProgress).isFalse()
        verify(exactly = 0) { aiReviewCodeCacheService.getCachedReview(any(), any()) }
        verifyNoGenerationStarted()
    }

    @Test
    @DisplayName("비동기 요청도 공백을 제외한 코드가 10자 미만이면 기본 메시지를 반환한다")
    fun `async code too short`() {
        givenLog(code = "   012345678   ")

        val result = aiReviewService.requestOneLineReviewAsync(LOG_ID, OWNER_ID)

        assertThat(result.review).isEqualTo("코드가 너무 짧아 분석할 수 없습니다")
        assertThat(result.cached).isFalse()
        assertThat(result.inProgress).isFalse()
        verify(exactly = 0) { aiReviewCodeCacheService.getCachedReview(any(), any()) }
        verifyNoGenerationStarted()
    }

    @Test
    @DisplayName("비동기 요청도 같은 코드의 리뷰 캐시가 있으면 잠금 없이 반환한다")
    fun `async code cache`() {
        givenLog(code = "  0123456789  ", isSuccess = true)
        every { aiReviewCodeCacheService.getCachedReview("0123456789", true) } returns "code review"

        val result = aiReviewService.requestOneLineReviewAsync(LOG_ID, OWNER_ID)

        assertThat(result.review).isEqualTo("code review")
        assertThat(result.cached).isTrue()
        assertThat(result.inProgress).isFalse()
        verifyNoGenerationStarted()
    }

    @Test
    @DisplayName("동기 요청은 공백을 제외한 코드가 10자이면 길이 조건을 통과해 코드 캐시를 확인한다")
    fun `sync minimum length boundary`() {
        givenLog(code = "  0123456789  ", isSuccess = false)
        every { aiReviewCodeCacheService.getCachedReview("0123456789", false) } returns "code review"

        val result = aiReviewService.requestOneLineReview(LOG_ID)

        assertThat(result.review).isEqualTo("code review")
        assertThat(result.cached).isTrue()
        verifyNoGenerationStarted()
    }

    @Test
    @DisplayName("동기 요청은 로그에 저장된 리뷰가 있으면 코드 캐시를 확인하지 않는다")
    fun `sync entity cache skips code cache`() {
        givenLog(code = "short", aiReview = AiReview("stored review"))

        val result = aiReviewService.requestOneLineReview(LOG_ID)

        assertThat(result.review).isEqualTo("stored review")
        assertThat(result.cached).isTrue()
        verify(exactly = 0) { aiReviewCodeCacheService.getCachedReview(any(), any()) }
        verifyNoGenerationStarted()
    }

    private fun givenLog(code: String, aiReview: AiReview? = null, isSuccess: Boolean? = null) {
        val log = Log(
            id = LOG_ID,
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode(code),
            studentId = OWNER_ID,
            isSuccess = isSuccess,
            aiReview = aiReview
        )
        every { logRepository.findById(LOG_ID) } returns Optional.of(log)
    }

    private fun verifyNoGenerationStarted() {
        verify { lockRepository wasNot Called }
        verify { aiApiClient wasNot Called }
        verify(exactly = 0) { aiUsageService.reserveUsage(any()) }
        verify { aiReviewTaskExecutor wasNot Called }
    }

    companion object {
        private const val LOG_ID = "log-immediate"
        private const val OWNER_ID = "owner-id"
    }
}
