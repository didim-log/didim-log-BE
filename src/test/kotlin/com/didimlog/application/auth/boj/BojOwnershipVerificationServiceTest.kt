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
import com.didimlog.global.exception.ErrorCode
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
    private val profileStatusMessageClient: BojProfileStatusMessageClient = mockk()
    private val service = BojOwnershipVerificationService(codeStore, studentRepository, profileStatusMessageClient)

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
    @DisplayName("상태 메시지에 코드가 있으면 학생을 인증 처리하고 코드를 삭제한다")
    fun `verifyOwnership marks student verified and deletes session`() {
        val bojId = "mekazon"
        val sessionId = "session"
        val storedCode = "DIDIM-LOG-ABC123"
        val student = student(bojId)

        every { codeStore.find(sessionId) } returns storedCode
        every { profileStatusMessageClient.fetchStatusMessage(bojId) } returns BojProfileStatusMessageFetchResult.Found(
            BojProfileStatusMessage("코드: $storedCode")
        )
        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { studentRepository.save(any()) } answers { firstArg() }
        every { codeStore.delete(sessionId) } just runs

        service.verifyOwnership(sessionId = sessionId, bojId = bojId)

        verify(exactly = 1) {
            studentRepository.save(
                match {
                    it.isVerified &&
                        it.role == Role.USER &&
                        it.bojId == BojId(bojId)
                }
            )
        }
        verify(exactly = 1) { codeStore.delete(sessionId) }
    }

    private fun student(bojId: String): Student {
        return Student(
            id = "id",
            nickname = Nickname("닉네임12"),
            provider = Provider.GOOGLE,
            providerId = "providerId",
            email = "test@example.com",
            bojId = BojId(bojId),
            password = null,
            rating = 0,
            currentTier = Tier.UNRATED,
            role = Role.GUEST,
            termsAgreed = false,
            isVerified = false,
            solutions = Solutions()
        )
    }
}

