package com.didimlog.application.auth.boj

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.ratelimit.RateLimitDecision
import com.didimlog.global.ratelimit.RateLimitException
import com.didimlog.global.ratelimit.RateLimitService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

@DisplayName("BOJ 소유권 인증 서비스 테스트")
class BojOwnershipVerificationServiceTest {

    private val codeStore: BojVerificationCodeStore = mockk()
    private val profileStatusMessageClient: BojProfileStatusMessageClient = mockk()
    private val rateLimitService: RateLimitService = mockk()
    private val service = BojOwnershipVerificationService(
        codeStore,
        profileStatusMessageClient,
        rateLimitService
    )

    @Test
    @DisplayName("인증 코드를 발급하면 sessionId와 함께 저장한다")
    fun `issue code saves into store`() {
        val identifier = "127.0.0.1"
        every {
            rateLimitService.checkAndRecord("boj_code:$identifier", 5, 1)
        } returns allowedDecision()
        every { codeStore.save(any(), any(), any()) } just runs

        val issued = service.issueVerificationCode(identifier)

        assertThat(issued.sessionId).isNotBlank()
        assertThat(issued.code).startsWith("DIDIM-LOG-")
        assertThat(issued.code.length).isGreaterThan("DIDIM-LOG-".length) // 코드 길이 확인
        verify(exactly = 1) {
            rateLimitService.checkAndRecord("boj_code:$identifier", 5, 1)
        }
        verify(exactly = 1) { codeStore.save(issued.sessionId, issued.code, issued.expiresInSeconds) }
    }

    @Test
    @DisplayName("포트폴리오 전용 프로필에서는 고정 인증 코드를 발급한다")
    fun `portfolio fixture profile issues deterministic code`() {
        val environment = MockEnvironment().apply {
            setActiveProfiles("portfolio-fixture")
        }
        val fixtureService = BojOwnershipVerificationService(
            codeStore,
            profileStatusMessageClient,
            rateLimitService,
            fixtureCode = "DIDIM-LOG-DEMO42",
            environment = environment
        )
        every {
            rateLimitService.checkAndRecord("boj_code:127.0.0.1", 5, 1)
        } returns allowedDecision()
        every { codeStore.save(any(), any(), any()) } just runs

        val issued = fixtureService.issueVerificationCode("127.0.0.1")

        assertThat(issued.code).isEqualTo("DIDIM-LOG-DEMO42")
    }

    @Test
    @DisplayName("운영 프로필과 함께 활성화되면 포트폴리오 고정 코드를 사용하지 않는다")
    fun `production profile disables deterministic fixture code`() {
        val environment = MockEnvironment().apply {
            setActiveProfiles("prod", "portfolio-fixture")
        }
        val productionService = BojOwnershipVerificationService(
            codeStore,
            profileStatusMessageClient,
            rateLimitService,
            fixtureCode = "DIDIM-LOG-DEMO42",
            environment = environment
        )
        every {
            rateLimitService.checkAndRecord("boj_code:127.0.0.1", 5, 1)
        } returns allowedDecision()
        every { codeStore.save(any(), any(), any()) } just runs

        val issued = productionService.issueVerificationCode("127.0.0.1")

        assertThat(issued.code).startsWith("DIDIM-LOG-")
        assertThat(issued.code).isNotEqualTo("DIDIM-LOG-DEMO42")
    }

    @Test
    @DisplayName("Rate Limit 초과 시 예외를 던진다")
    fun `rate limit exceeded throws exception`() {
        val identifier = "127.0.0.1"
        every {
            rateLimitService.checkAndRecord("boj_code:$identifier", 5, 1)
        } returns RateLimitDecision(
            allowed = false,
            limit = 5,
            remainingRequests = 0,
            retryAfterSeconds = 42L
        )

        assertThatThrownBy { service.issueVerificationCode(identifier) }
            .isInstanceOf(RateLimitException::class.java)
            .hasMessageContaining("요청이 너무 많습니다")
            .hasFieldOrPropertyWithValue("retryAfterSeconds", 42L)
            .hasFieldOrPropertyWithValue("limit", 5)

        verify(exactly = 0) { codeStore.save(any(), any(), any()) }
    }

    @Test
    @DisplayName("세션에 저장된 코드가 없으면 예외를 던진다")
    fun `verifyOwnership throws when code expired`() {
        every { codeStore.find(any()) } returns null

        assertThatThrownBy { service.verifyOwnership(sessionId = "session", bojId = "mekazon") }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
    }

    @Test
    @DisplayName("BOJ 프로필이 404면 COMMON_RESOURCE_NOT_FOUND를 던진다")
    fun `verifyOwnership throws when boj profile not found`() {
        every { codeStore.find(any()) } returns "DIDIM-LOG-ABC123"
        every { profileStatusMessageClient.fetchStatusMessage(any()) } returns BojProfileStatusMessageFetchResult.UserNotFound

        assertThatThrownBy { service.verifyOwnership(sessionId = "session", bojId = "mekazon") }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
    }

    @Test
    @DisplayName("상태 메시지를 찾을 수 없으면 COMMON_INVALID_INPUT를 던진다")
    fun `verifyOwnership throws when status message not found`() {
        every { codeStore.find(any()) } returns "DIDIM-LOG-ABC123"
        every { profileStatusMessageClient.fetchStatusMessage(any()) } returns BojProfileStatusMessageFetchResult.StatusMessageNotFound

        assertThatThrownBy { service.verifyOwnership(sessionId = "session", bojId = "mekazon") }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
    }

    @Test
    @DisplayName("상태 메시지에 코드가 없으면 COMMON_INVALID_INPUT를 던진다")
    fun `verifyOwnership throws when code not present`() {
        every { codeStore.find(any()) } returns "DIDIM-LOG-ABC123"
        every { profileStatusMessageClient.fetchStatusMessage(any()) } returns BojProfileStatusMessageFetchResult.Found(
            BojProfileStatusMessage("hello-world")
        )

        assertThatThrownBy { service.verifyOwnership(sessionId = "session", bojId = "mekazon") }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
    }

    @Test
    @DisplayName("상태 메시지에 코드가 있으면 인증된 BOJ ID를 반환하고 세션을 저장한다")
    fun `verifyOwnership returns verified bojId and saves to session`() {
        val bojId = "mekazon"
        val sessionId = "session"
        val storedCode = "DIDIM-LOG-ABC123"

        every { codeStore.find(sessionId) } returns storedCode
        every { profileStatusMessageClient.fetchStatusMessage(bojId) } returns BojProfileStatusMessageFetchResult.Found(
            BojProfileStatusMessage("코드: $storedCode")
        )
        every { codeStore.consume(sessionId) } returns storedCode
        every { codeStore.save(any(), any(), any()) } just runs

        val verifiedBojId = service.verifyOwnership(sessionId = sessionId, bojId = bojId)

        assertThat(verifiedBojId).isEqualTo(bojId)
        verify(exactly = 1) { codeStore.consume(sessionId) }
        verify(exactly = 1) { 
            codeStore.save(
                "boj:verified:$sessionId",
                bojId,
                300L
            )
        }
    }

    @Test
    @DisplayName("동시에 인증 코드를 사용하면 코드를 claim한 요청만 인증 세션을 저장한다")
    fun `verifyOwnership saves proof only after claiming code`() {
        val storedCode = "DIDIM-LOG-ABC123"
        every { codeStore.find("session") } returns storedCode
        every { profileStatusMessageClient.fetchStatusMessage("mekazon") } returns
            BojProfileStatusMessageFetchResult.Found(BojProfileStatusMessage(storedCode))
        every { codeStore.consume("session") } returns null

        assertThatThrownBy {
            service.verifyOwnership(sessionId = "session", bojId = "mekazon")
        }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("이미 사용")

        verify(exactly = 0) {
            codeStore.save("boj:verified:session", any(), any())
        }
    }

    @Test
    @DisplayName("인증한 BOJ ID와 가입 요청의 BOJ ID가 같으면 인증 세션을 소비한다")
    fun `consumeVerifiedBojId consumes matching session`() {
        every { codeStore.find("boj:verified:session") } returns "mekazon"
        every { codeStore.consume("boj:verified:session") } returns "mekazon"

        service.consumeVerifiedBojId(verificationSessionId = "session", bojId = "mekazon")

        verify(exactly = 1) { codeStore.find("boj:verified:session") }
        verify(exactly = 1) { codeStore.consume("boj:verified:session") }
    }

    @Test
    @DisplayName("인증 세션이 만료되었거나 이미 사용됐으면 가입을 거부한다")
    fun `consumeVerifiedBojId rejects missing session`() {
        every { codeStore.find("boj:verified:session") } returns null

        assertThatThrownBy {
            service.consumeVerifiedBojId(verificationSessionId = "session", bojId = "mekazon")
        }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("만료되었거나 이미 사용")
            .hasMessageContaining("다시 진행")
    }

    @Test
    @DisplayName("인증한 BOJ ID와 가입 요청의 BOJ ID가 다르면 가입을 거부한다")
    fun `consumeVerifiedBojId rejects mismatched bojId`() {
        every { codeStore.find("boj:verified:session") } returns "verifiedUser"

        assertThatThrownBy {
            service.consumeVerifiedBojId(verificationSessionId = "session", bojId = "otherUser")
        }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("일치하지 않습니다")

        verify(exactly = 0) { codeStore.consume("boj:verified:session") }
    }

    @Test
    @DisplayName("한 번 사용한 인증 세션은 다시 사용할 수 없다")
    fun `consumeVerifiedBojId rejects reused session`() {
        every { codeStore.find("boj:verified:session") } returnsMany listOf("mekazon", null)
        every { codeStore.consume("boj:verified:session") } returns "mekazon"

        service.consumeVerifiedBojId(verificationSessionId = "session", bojId = "mekazon")

        assertThatThrownBy {
            service.consumeVerifiedBojId(verificationSessionId = "session", bojId = "mekazon")
        }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("만료되었거나 이미 사용")

        verify(exactly = 1) { codeStore.consume("boj:verified:session") }
    }

    @Test
    @DisplayName("조회 뒤 다른 요청이 인증 세션을 소비하면 가입을 거부한다")
    fun `consumeVerifiedBojId rejects concurrent consumption`() {
        every { codeStore.find("boj:verified:session") } returns "mekazon"
        every { codeStore.consume("boj:verified:session") } returns null

        assertThatThrownBy {
            service.consumeVerifiedBojId(verificationSessionId = "session", bojId = "mekazon")
        }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("만료되었거나 이미 사용")
    }

    private fun allowedDecision(): RateLimitDecision {
        return RateLimitDecision(
            allowed = true,
            limit = 5,
            remainingRequests = 4,
            retryAfterSeconds = null
        )
    }
}
