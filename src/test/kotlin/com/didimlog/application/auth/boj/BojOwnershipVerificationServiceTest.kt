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
    private val statusClient: BojProfileStatusMessageClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val service = BojOwnershipVerificationService(codeStore, statusClient, studentRepository)

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

    @Test
    @DisplayName("상태 메시지에 코드가 없으면 예외를 던진다")
    fun `verify fails when code not found`() {
        every { codeStore.find("session") } returns "DIDIM-LOG-ABCDEF"
        every { statusClient.fetchStatusMessage("test") } returns "hello"

        assertThatThrownBy { service.verifyOwnership("session", "test") }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("상태 메시지에서 코드를 찾을 수 없습니다")
    }

    @Test
    @DisplayName("상태 메시지에 부분적으로 일치하는 코드가 있으면 예외를 던진다 (부분 문자열 일치 방지)")
    fun `verify fails when code partially matches`() {
        every { codeStore.find("session") } returns "DIDIM-LOG-1234"
        every { statusClient.fetchStatusMessage("test") } returns "DIDIM-LOG-12345"

        assertThatThrownBy { service.verifyOwnership("session", "test") }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("상태 메시지에서 코드를 찾을 수 없습니다")
    }

    @Test
    @DisplayName("상태 메시지에 코드가 있으면 Student.isVerified를 true로 업데이트한다")
    fun `verify updates student`() {
        val student = Student(
            id = "student-1",
            nickname = Nickname("user1"),
            provider = Provider.BOJ,
            providerId = "user1",
            bojId = BojId("test"),
            password = "encoded",
            rating = 1000,
            currentTier = Tier.fromRating(1000),
            role = Role.GUEST,
            termsAgreed = true,
            isVerified = false,
            solutions = Solutions()
        )

        val code = "DIDIM-LOG-ABCDEF"
        every { codeStore.find("session") } returns code
        every { statusClient.fetchStatusMessage("test") } returns "상태 메시지 $code 입니다"
        every { studentRepository.findByBojId(BojId("test")) } returns Optional.of(student)
        every { studentRepository.save(any()) } answers { firstArg() }
        every { codeStore.delete("session") } just runs

        service.verifyOwnership("session", "test")

        verify(exactly = 1) {
            studentRepository.save(withArg { updated ->
                assertThat(updated.isVerified).isTrue
                assertThat(updated.role).isEqualTo(Role.USER)
            })
        }
        verify(exactly = 1) { codeStore.delete("session") }
    }

    @Test
    @DisplayName("상태 메시지가 코드와 정확히 일치하면 검증 성공")
    fun `verify succeeds when status message exactly matches code`() {
        val student = Student(
            id = "student-1",
            nickname = Nickname("user1"),
            provider = Provider.BOJ,
            providerId = "user1",
            bojId = BojId("test"),
            password = "encoded",
            rating = 1000,
            currentTier = Tier.fromRating(1000),
            role = Role.GUEST,
            termsAgreed = true,
            isVerified = false,
            solutions = Solutions()
        )

        val code = "DIDIM-LOG-ABCDEF"
        every { codeStore.find("session") } returns code
        every { statusClient.fetchStatusMessage("test") } returns code
        every { studentRepository.findByBojId(BojId("test")) } returns Optional.of(student)
        every { studentRepository.save(any()) } answers { firstArg() }
        every { codeStore.delete("session") } just runs

        service.verifyOwnership("session", "test")

        verify(exactly = 1) { studentRepository.save(any()) }
        verify(exactly = 1) { codeStore.delete("session") }
    }
}

