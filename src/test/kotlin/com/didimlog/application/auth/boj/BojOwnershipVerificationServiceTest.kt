package com.didimlog.application.auth.boj

import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BOJ 소유권 인증 서비스 테스트")
class BojOwnershipVerificationServiceTest {

    private val codeStore: BojVerificationCodeStore = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val service = BojOwnershipVerificationService(codeStore, studentRepository)

    @Test
    @DisplayName("인증 코드를 발급하면 sessionId와 함께 저장한다")
    fun `issue code saves into store`() {
        val identifier = "127.0.0.1"
        every { codeStore.getRateLimitCount(any()) } returns 0L
        every { codeStore.incrementRateLimitCount(any(), any()) } just runs
        every { codeStore.save(any(), any(), any()) } just runs

        val issued = service.issueVerificationCode(identifier)

        assertThat(issued.sessionId).isNotBlank()
        assertThat(issued.code).startsWith("DIDIM-LOG-")
        assertThat(issued.code.length).isGreaterThan("DIDIM-LOG-".length) // 코드 길이 확인
        verify(exactly = 1) { codeStore.getRateLimitCount(any()) }
        verify(exactly = 1) { codeStore.incrementRateLimitCount(any(), any()) }
        verify(exactly = 1) { codeStore.save(issued.sessionId, issued.code, issued.expiresInSeconds) }
    }

    @Test
    @DisplayName("Rate Limit 초과 시 예외를 던진다")
    fun `rate limit exceeded throws exception`() {
        val identifier = "127.0.0.1"
        every { codeStore.getRateLimitCount(any()) } returns 5L

        assertThatThrownBy { service.issueVerificationCode(identifier) }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("요청이 너무 많습니다")
    }

    // Note: verifyOwnership 테스트는 실제 네트워크 요청(Jsoup)이 필요하므로 통합 테스트로 분리되어야 합니다.
    // 단위 테스트에서는 issueVerificationCode와 Rate Limiting 로직만 테스트합니다.
}

