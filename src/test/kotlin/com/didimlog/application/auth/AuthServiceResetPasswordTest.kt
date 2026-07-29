package com.didimlog.application.auth

import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.InvalidPasswordException
import com.didimlog.infra.email.EmailService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime

@DisplayName("AuthService 비밀번호 재설정 테스트")
class AuthServiceResetPasswordTest {

    private val solvedAcClient = mockk<com.didimlog.infra.solvedac.SolvedAcClient>(relaxed = true)
    private val studentRepository = mockk<StudentRepository>(relaxed = true)
    private val jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>(relaxed = true)
    private val emailService = mockk<EmailService>(relaxed = true)
    private val passwordResetCodeRepository = mockk<PasswordResetCodeRepository>(relaxed = true)
    private val passwordResetCodeGenerator = mockk<PasswordResetCodeGenerator>(relaxed = true)
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val bojOwnershipVerificationService =
        mockk<com.didimlog.application.auth.boj.BojOwnershipVerificationService>(relaxed = true)

    private val authService = AuthService(
        solvedAcClient = solvedAcClient,
        studentRepository = studentRepository,
        jwtTokenProvider = jwtTokenProvider,
        passwordEncoder = passwordEncoder,
        emailService = emailService,
        passwordResetCodeRepository = passwordResetCodeRepository,
        passwordResetCodeGenerator = passwordResetCodeGenerator,
        refreshTokenService = refreshTokenService,
        bojOwnershipVerificationService = bojOwnershipVerificationService
    )

    @Test
    @DisplayName("resetPassword는 유효한 재설정 코드와 새 비밀번호로 비밀번호를 변경한다")
    fun `비밀번호 재설정 성공`() {
        // given
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val newPassword = "NewPassword123!"
        val encodedPassword = "encodedNewPassword"

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.consumeByResetCode(resetCode) } returns passwordResetCode
        every { passwordEncoder.encode(newPassword) } returns encodedPassword
        every { studentRepository.updatePasswordById(studentId, encodedPassword) } returns true

        // when
        authService.resetPassword(resetCode, newPassword)

        // then
        verify(exactly = 1) { passwordResetCodeRepository.consumeByResetCode(resetCode) }
        verify(exactly = 1) { passwordEncoder.encode(newPassword) }
        verify(exactly = 1) { studentRepository.updatePasswordById(studentId, encodedPassword) }
    }

    @Test
    @DisplayName("resetPassword는 유효하지 않은 재설정 코드에 대해 예외를 발생시킨다")
    fun `비밀번호 재설정 실패 - 유효하지 않은 코드`() {
        // given
        val resetCode = "INVALID"
        val newPassword = "NewPassword123!"

        every { passwordResetCodeRepository.consumeByResetCode(resetCode) } returns null

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("유효하지 않은 재설정 코드")

        verify(exactly = 1) { passwordResetCodeRepository.consumeByResetCode(resetCode) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.updatePasswordById(any(), any()) }
    }

    @Test
    @DisplayName("resetPassword는 재설정 코드로 찾은 학생이 없으면 예외를 발생시킨다")
    fun `비밀번호 재설정 실패 - 학생 없음`() {
        // given
        val resetCode = "ABC12345"
        val studentId = "non-existent-student-id"
        val newPassword = "NewPassword123!"

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.consumeByResetCode(resetCode) } returns passwordResetCode
        every { passwordEncoder.encode(newPassword) } returns "encodedNewPassword"
        every { studentRepository.updatePasswordById(studentId, "encodedNewPassword") } returns false

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)

        verify(exactly = 1) { passwordResetCodeRepository.consumeByResetCode(resetCode) }
        verify(exactly = 1) { passwordEncoder.encode(newPassword) }
        verify(exactly = 1) { studentRepository.updatePasswordById(studentId, "encodedNewPassword") }
    }

    @Test
    @DisplayName("resetPassword는 만료된 재설정 코드를 소비하고 비밀번호를 변경하지 않는다")
    fun `비밀번호 재설정 실패 - 만료된 코드`() {
        // given
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val newPassword = "NewPassword123!"

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = LocalDateTime.now().minusMinutes(1)
        )

        every { passwordResetCodeRepository.consumeByResetCode(resetCode) } returns passwordResetCode

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("만료된 재설정 코드")

        verify(exactly = 1) { passwordResetCodeRepository.consumeByResetCode(resetCode) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.updatePasswordById(any(), any()) }
    }

    @Test
    @DisplayName("resetPassword는 비밀번호 정책 위반 시 재설정 코드를 소비하지 않는다")
    fun `비밀번호 재설정 실패 - 비밀번호 정책 위반`() {
        // given
        val invalidPassword = "short123"

        // when & then
        assertThrows<InvalidPasswordException> {
            authService.resetPassword("ABC12345", invalidPassword)
        }

        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.updatePasswordById(any(), any()) }
    }
}
