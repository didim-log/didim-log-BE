package com.didimlog.application.auth

import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.InvalidPasswordException
import com.didimlog.infra.email.EmailService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.util.Optional

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

    private val authService = createAuthService()

    @Test
    @DisplayName("resetPassword는 유효한 재설정 코드와 새 비밀번호로 비밀번호를 변경한다")
    fun `비밀번호 재설정 성공`() {
        // given
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val newPassword = "NewPassword123!"
        val encodedPassword = "encodedNewPassword"
        val existingStudent = student(studentId).copy(credentialVersion = 7)

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = existingStudent.credentialVersion,
            bojId = requireNotNull(existingStudent.bojId).value,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        } returns passwordResetCode
        every { studentRepository.findById(studentId) } returns Optional.of(existingStudent)
        every { passwordEncoder.encode(newPassword) } returns encodedPassword
        every {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                existingStudent.credentialVersion,
                requireNotNull(existingStudent.bojId)
            )
        } returns true

        // when
        authService.resetPassword(resetCode, newPassword)

        // then
        verify(exactly = 1) {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        }
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 1) { passwordEncoder.encode(newPassword) }
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
        verify(exactly = 1) {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                existingStudent.credentialVersion,
                requireNotNull(existingStudent.bojId)
            )
        }
    }

    @Test
    @DisplayName("resetPassword는 유효하지 않은 재설정 코드에 대해 예외를 발생시킨다")
    fun `비밀번호 재설정 실패 - 유효하지 않은 코드`() {
        // given
        val resetCode = "INVALID"
        val newPassword = "NewPassword123!"

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("유효하지 않은 재설정 코드")

        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any(), any()) }
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
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
            credentialVersion = 0,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        } returns passwordResetCode
        every { studentRepository.findById(studentId) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)

        verify(exactly = 1) {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        }
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 0) { passwordEncoder.encode(newPassword) }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
    }

    @Test
    @DisplayName("resetPassword는 발급 당시 BOJ ID와 다른 계정의 세션과 비밀번호를 변경하지 않는다")
    fun `비밀번호 재설정 실패 - BOJ ID 없음`() {
        val resetCode = "ABC12345"
        val studentId = "social-student-id"
        val newPassword = "NewPassword123!"
        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = 0,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )
        val socialStudent = student(studentId).copy(bojId = null)

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        } returns passwordResetCode
        every { studentRepository.findById(studentId) } returns Optional.of(socialStudent)

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any(), any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
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
            credentialVersion = 0,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().minusMinutes(1)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))
        every {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        } returns passwordResetCode

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("만료된 재설정 코드")

        verify(exactly = 1) {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        }
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
    }

    @Test
    @DisplayName("resetPassword는 비밀번호 변경 후 Refresh Token 폐기 실패를 전달한다")
    fun `비밀번호 재설정 실패 - 세션 폐기 실패`() {
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val newPassword = "NewPassword123!"
        val encodedPassword = "encodedNewPassword"
        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = 0,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        } returns passwordResetCode
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))
        every { passwordEncoder.encode(newPassword) } returns encodedPassword
        every {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                0L,
                BojId("reset_user")
            )
        } returns true
        every {
            refreshTokenService.revokeAllForStudent(studentId)
        } throws IllegalStateException("Redis unavailable")

        assertThrows<IllegalStateException> {
            authService.resetPassword(resetCode, newPassword)
        }

        verify(exactly = 1) {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                0L,
                BojId("reset_user")
            )
        }
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
        verifyOrder {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                0L,
                BojId("reset_user")
            )
            refreshTokenService.revokeAllForStudent(studentId)
        }
    }

    @Test
    @DisplayName("resetPassword는 비밀번호 CAS 충돌 시 세션을 폐기하지 않고 실패한다")
    fun `비밀번호 재설정 실패 - 비밀번호 CAS 충돌`() {
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val newPassword = "NewPassword123!"
        val encodedPassword = "encodedNewPassword"
        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = 0,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        } returns passwordResetCode
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))
        every { passwordEncoder.encode(newPassword) } returns encodedPassword
        every {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                0L,
                BojId("reset_user")
            )
        } returns false

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        assertThat(exception.errorCode.retryable).isFalse()
        assertThat(exception.message).contains("새 재설정 코드")
        verify(exactly = 1) {
            passwordResetCodeRepository.consumeByResetCode(resetCode, studentId)
        }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 1) {
            studentRepository.updatePasswordById(
                studentId,
                encodedPassword,
                0L,
                BojId("reset_user")
            )
        }
    }

    @Test
    @DisplayName("resetPassword는 권한 변경 전에 발급한 코드를 소비하지 않고 거절한다")
    fun `비밀번호 재설정 실패 - 권한 변경 전 발급 코드`() {
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = 3,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )
        val promotedStudent = student(studentId).copy(
            role = Role.ADMIN,
            credentialVersion = 4
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every { studentRepository.findById(studentId) } returns Optional.of(promotedStudent)

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, "NewPassword123!")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any(), any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
    }

    @Test
    @DisplayName("resetPassword는 발급 버전이 없는 기존 코드를 소비하지 않고 거절한다")
    fun `비밀번호 재설정 실패 - 발급 버전 없는 기존 코드`() {
        val resetCode = "LEGACY01"
        val studentId = "student-id"
        val legacyResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(legacyResetCode)
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))

        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, "NewPassword123!")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PASSWORD_RESET_CONFLICT)
        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any(), any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
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

        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any(), any()) }
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
    }

    @Test
    @DisplayName("resetPassword는 세션 잠금 충돌 시 재설정 코드를 소비하지 않는다")
    fun `비밀번호 재설정 실패 - 세션 잠금 충돌`() {
        assertCoordinatorFailurePreservesResetCode(ErrorCode.SESSION_STATE_CONFLICT)
    }

    @Test
    @DisplayName("resetPassword는 세션 상태 저장소 장애 시 재설정 코드를 소비하지 않는다")
    fun `비밀번호 재설정 실패 - 세션 상태 저장소 장애`() {
        assertCoordinatorFailurePreservesResetCode(ErrorCode.SESSION_STATE_UNAVAILABLE)
    }

    private fun assertCoordinatorFailurePreservesResetCode(errorCode: ErrorCode) {
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            credentialVersion = 0,
            bojId = "reset_user",
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )
        val rejectingAuthService = createAuthService(RejectingCredentialSessionCoordinator(errorCode))

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)

        val exception = assertThrows<BusinessException> {
            rejectingAuthService.resetPassword(resetCode, "NewPassword123!")
        }

        assertThat(exception.errorCode).isEqualTo(errorCode)
        verify(exactly = 1) { passwordResetCodeRepository.findByResetCode(resetCode) }
        verify(exactly = 0) { passwordResetCodeRepository.consumeByResetCode(any(), any()) }
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) {
            studentRepository.updatePasswordById(any(), any(), any(), BojId("reset_user"))
        }
    }

    private fun createAuthService(
        credentialSessionCoordinator: CredentialSessionCoordinator = ImmediateCredentialSessionCoordinator()
    ): AuthService {
        return AuthService(
            solvedAcClient = solvedAcClient,
            studentRepository = studentRepository,
            jwtTokenProvider = jwtTokenProvider,
            passwordEncoder = passwordEncoder,
            emailService = emailService,
            passwordResetCodeRepository = passwordResetCodeRepository,
            passwordResetCodeGenerator = passwordResetCodeGenerator,
            refreshTokenService = refreshTokenService,
            bojOwnershipVerificationService = bojOwnershipVerificationService,
            credentialSessionCoordinator = credentialSessionCoordinator
        )
    }

    private class RejectingCredentialSessionCoordinator(
        private val errorCode: ErrorCode
    ) : CredentialSessionCoordinator {
        override fun <T> execute(studentId: String, action: () -> T): T {
            throw BusinessException(errorCode)
        }

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            throw BusinessException(errorCode)
        }
    }

    private fun student(studentId: String): Student {
        return Student(
            id = studentId,
            nickname = Nickname("resetuser"),
            provider = Provider.BOJ,
            providerId = "reset_user",
            bojId = BojId("reset_user"),
            password = "old-encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }
}
