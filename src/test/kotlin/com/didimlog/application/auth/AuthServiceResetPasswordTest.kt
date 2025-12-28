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
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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

    private val authService = AuthService(
        solvedAcClient = solvedAcClient,
        studentRepository = studentRepository,
        jwtTokenProvider = jwtTokenProvider,
        passwordEncoder = passwordEncoder,
        emailService = emailService,
        passwordResetCodeRepository = passwordResetCodeRepository
    )

    @Test
    @DisplayName("resetPassword는 유효한 재설정 코드와 새 비밀번호로 비밀번호를 변경한다")
    fun `비밀번호 재설정 성공`() {
        // given
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val newPassword = "NewPassword123!"
        val encodedPassword = "encodedNewPassword"
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            email = "test@example.com",
            bojId = BojId("testuser"),
            password = "oldEncodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        mockkObject(com.didimlog.global.util.PasswordValidator)
        every { com.didimlog.global.util.PasswordValidator.validate(newPassword) } returns Unit
        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { passwordEncoder.encode(newPassword) } returns encodedPassword
        every { studentRepository.save(any()) } answers { firstArg() }
        every { passwordResetCodeRepository.deleteByResetCode(resetCode) } returns Unit

        // when
        authService.resetPassword(resetCode, newPassword)

        // then
        verify(exactly = 1) { com.didimlog.global.util.PasswordValidator.validate(newPassword) }
        verify(exactly = 1) { passwordResetCodeRepository.findByResetCode(resetCode) }
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 1) { passwordEncoder.encode(newPassword) }
        verify(exactly = 1) {
            studentRepository.save(
                match { it.password == encodedPassword }
            )
        }
        verify(exactly = 1) { passwordResetCodeRepository.deleteByResetCode(resetCode) }
        unmockkObject(com.didimlog.global.util.PasswordValidator)
    }

    @Test
    @DisplayName("resetPassword는 유효하지 않은 재설정 코드에 대해 예외를 발생시킨다")
    fun `비밀번호 재설정 실패 - 유효하지 않은 코드`() {
        // given
        val resetCode = "INVALID"
        val newPassword = "NewPassword123!"

        mockkObject(com.didimlog.global.util.PasswordValidator)
        every { com.didimlog.global.util.PasswordValidator.validate(newPassword) } returns Unit
        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("유효하지 않은 재설정 코드")

        verify(exactly = 1) { passwordResetCodeRepository.findByResetCode(resetCode) }
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.save(any()) }
        unmockkObject(com.didimlog.global.util.PasswordValidator)
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

        mockkObject(com.didimlog.global.util.PasswordValidator)
        every { com.didimlog.global.util.PasswordValidator.validate(newPassword) } returns Unit
        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every { studentRepository.findById(studentId) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.resetPassword(resetCode, newPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)

        verify(exactly = 1) { passwordResetCodeRepository.findByResetCode(resetCode) }
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.save(any()) }
        verify(exactly = 0) { passwordResetCodeRepository.deleteByResetCode(any()) }
        unmockkObject(com.didimlog.global.util.PasswordValidator)
    }

    @Test
    @DisplayName("resetPassword는 비밀번호 정책 위반 시 예외를 발생시킨다")
    fun `비밀번호 재설정 실패 - 비밀번호 정책 위반`() {
        // given
        val resetCode = "ABC12345"
        val studentId = "student-id"
        val invalidPassword = "short123" // 8자이지만 영문, 숫자만 있음 (2종류) -> 10자 이상 필요
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = "testuser",
            email = "test@example.com",
            bojId = BojId("testuser"),
            password = "oldEncodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        every { passwordResetCodeRepository.findByResetCode(resetCode) } returns Optional.of(passwordResetCode)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        // PasswordValidator.validate()가 호출되기 전에 findByResetCode()와 findById()가 호출되므로, 둘 다 mock해야 함
        // validate()에서 예외가 발생하므로, 그 이후 단계는 진행되지 않음

        // when & then
        // PasswordValidator.validate()는 실제로 호출되며, InvalidPasswordException을 발생시킴
        // InvalidPasswordException은 RuntimeException이므로 그대로 전파됨
        assertThrows<InvalidPasswordException> {
            authService.resetPassword(resetCode, invalidPassword)
        }

        verify(exactly = 1) { passwordResetCodeRepository.findByResetCode(resetCode) }
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.save(any()) }
    }
}

