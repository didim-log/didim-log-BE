package com.didimlog.auth.application.boj

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

    private fun verifyWith(fetchResult: BojProfileStatusMessageFetchResult): BusinessException {
        every { codeStore.find("session") } returns "DIDIM-LOG-ABC123"
        every { profileStatusMessageClient.fetchStatusMessage("mekazon") } returns fetchResult

        return assertThrows<BusinessException> {
            service.verifyOwnership(sessionId = "session", bojId = "mekazon")
        }
    }
}
