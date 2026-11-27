package com.didimlog.auth.application.boj

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.ratelimit.RateLimitService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory

@DisplayName("BOJ 소유권 인증 실패 처리 테스트")
class BojOwnershipVerificationFailureTest {

    private val codeStore: BojVerificationCodeStore = mockk()
    private val profileStatusMessageClient: BojProfileStatusMessageClient = mockk()
    private val rateLimitService: RateLimitService = mockk()
    private val service = BojOwnershipVerificationService(
        codeStore,
        profileStatusMessageClient,
        rateLimitService
    )

    @Test
    @DisplayName("BOJ 프로필 접근이 거부되면 COMMON_INVALID_INPUT을 던지고 코드를 소비하지 않는다")
    fun `access denied`() {
        val exception = verifyWith(BojProfileStatusMessageFetchResult.AccessDenied)

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).isEqualTo(
            "백준 프로필 페이지에 접근할 수 없습니다. 프로필이 공개되어 있는지 확인해주세요. bojId=mekazon"
        )
        verify(exactly = 0) { codeStore.consume(any()) }
    }

    @Test
    @DisplayName("BOJ 프로필 조회가 실패하면 COMMON_INTERNAL_ERROR를 던지고 코드를 소비하지 않는다")
    fun `fetch failed`() {
        val exception = verifyWith(BojProfileStatusMessageFetchResult.Failed("timeout"))

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(exception.message).isEqualTo(
            "백준 프로필 페이지를 가져오는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요. bojId=mekazon"
        )
        verify(exactly = 0) { codeStore.consume(any()) }
    }

    @Test
    @DisplayName("BOJ ID 앞뒤 공백을 제거한 값으로 프로필을 조회한다")
    fun `trimmed boj id`() {
        every { codeStore.find("session") } returns "DIDIM-LOG-ABC123"
        every {
            profileStatusMessageClient.fetchStatusMessage("mekazon")
        } returns BojProfileStatusMessageFetchResult.UserNotFound

        val exception = assertThrows<BusinessException> {
            service.verifyOwnership(sessionId = "session", bojId = "  mekazon  ")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).endsWith("bojId=mekazon")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureResults")
    @DisplayName("프로필 조회 실패 로그에는 BOJ ID를 마스킹해 남긴다")
    fun `failure logs mask boj id`(fetchResult: BojProfileStatusMessageFetchResult) {
        val logger = LoggerFactory.getLogger(BojOwnershipVerificationService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            verifyWith(fetchResult)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        val messages = appender.list.map { it.formattedMessage }
        assertThat(messages).hasSize(1)
        assertThat(messages.single())
            .contains("bojId=m***")
            .doesNotContain("mekazon")
    }

    private fun verifyWith(fetchResult: BojProfileStatusMessageFetchResult): BusinessException {
        every { codeStore.find("session") } returns "DIDIM-LOG-ABC123"
        every { profileStatusMessageClient.fetchStatusMessage("mekazon") } returns fetchResult

        return assertThrows<BusinessException> {
            service.verifyOwnership(sessionId = "session", bojId = "mekazon")
        }
    }

    companion object {
        @JvmStatic
        fun failureResults(): List<BojProfileStatusMessageFetchResult> = listOf(
            BojProfileStatusMessageFetchResult.UserNotFound,
            BojProfileStatusMessageFetchResult.AccessDenied,
            BojProfileStatusMessageFetchResult.StatusMessageNotFound,
            BojProfileStatusMessageFetchResult.Failed("timeout")
        )
    }
}
