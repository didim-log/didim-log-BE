package com.didimlog.application.auth

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
import java.util.Optional

@DisplayName("AuthService 아이디/비밀번호 찾기 테스트")
class AuthServiceFindIdPasswordTest {

    private val solvedAcClient = mockk<com.didimlog.infra.solvedac.SolvedAcClient>(relaxed = true)
    private val studentRepository = mockk<StudentRepository>(relaxed = true)
    private val jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>(relaxed = true)
    private val emailService = mockk<EmailService>(relaxed = true)
    private val passwordResetCodeRepository = mockk<PasswordResetCodeRepository>(relaxed = true)
    private val refreshTokenService = mockk<com.didimlog.application.auth.RefreshTokenService>(relaxed = true)

    private val authService = AuthService(
        solvedAcClient = solvedAcClient,
        studentRepository = studentRepository,
        jwtTokenProvider = jwtTokenProvider,
        passwordEncoder = passwordEncoder,
        emailService = emailService,
        passwordResetCodeRepository = passwordResetCodeRepository,
        refreshTokenService = refreshTokenService
    )

    @Test
    @DisplayName("findId는 이메일로 가입된 사용자의 BOJ ID를 이메일로 전송한다")
    fun `아이디 찾기 성공`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            email = email,
            bojId = BojId(bojId),
            password = "encodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when
        authService.findId(email)

        // then
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                to = email,
                subject = "[디딤로그] 아이디 찾기",
                templateName = "mail/find-id",
                variables = match {
                    it["nickname"] == "testuser" && it["bojId"] == bojId
                }
            )
        }
    }

    @Test
    @DisplayName("findId는 이메일로 가입된 사용자가 없으면 예외를 발생시킨다")
    fun `아이디 찾기 실패 - 사용자 없음`() {
        // given
        val email = "notfound@example.com"
        every { studentRepository.findByEmail(email) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findId(email)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 0) { emailService.sendTemplateEmail(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("findId는 BOJ ID가 등록되지 않은 계정이면 예외를 발생시킨다")
    fun `아이디 찾기 실패 - BOJ ID 없음`() {
        // given
        val email = "test@example.com"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.GOOGLE,
            providerId = "google123",
            email = email,
            bojId = null,
            password = null,
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findId(email)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
    }

    @Test
    @DisplayName("findPassword는 이메일과 BOJ ID가 일치하는 사용자에게 비밀번호 재설정 코드를 이메일로 전송한다")
    fun `비밀번호 찾기 성공`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            email = email,
            bojId = BojId(bojId),
            password = "encodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)
        every { passwordResetCodeRepository.save(any()) } answers { firstArg() }

        // when
        authService.findPassword(email, bojId)

        // then
        verify(exactly = 1) {
            passwordResetCodeRepository.save(
                match {
                    it.resetCode.length == 8 &&
                    it.studentId == studentId &&
                    it.expiresAt.isAfter(LocalDateTime.now())
                }
            )
        }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.save(any()) }
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                to = email,
                subject = "[디딤로그] 비밀번호 재설정",
                templateName = "mail/find-password",
                variables = match {
                    it["nickname"] == "testuser" &&
                    it["email"] == email &&
                    it["bojId"] == bojId &&
                    it.containsKey("resetCode")
                }
            )
        }
    }

    @Test
    @DisplayName("findPassword는 이메일로 가입된 사용자가 없으면 예외를 발생시킨다")
    fun `비밀번호 찾기 실패 - 사용자 없음`() {
        // given
        val email = "notfound@example.com"
        val bojId = "testuser"
        every { studentRepository.findByEmail(email) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, bojId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 0) { emailService.sendTemplateEmail(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("findPassword는 이메일과 BOJ ID가 일치하지 않으면 예외를 발생시킨다")
    fun `비밀번호 찾기 실패 - 이메일과 BOJ ID 불일치`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val differentBojId = "differentuser"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            email = email,
            bojId = BojId(bojId),
            password = "encodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, differentBojId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
    }

    @Test
    @DisplayName("findPassword는 비밀번호가 설정되지 않은 계정이면 예외를 발생시킨다")
    fun `비밀번호 찾기 실패 - 비밀번호 없음`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.GOOGLE,
            providerId = "google123",
            email = email,
            bojId = BojId(bojId),
            password = null,
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, bojId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
    }
}


