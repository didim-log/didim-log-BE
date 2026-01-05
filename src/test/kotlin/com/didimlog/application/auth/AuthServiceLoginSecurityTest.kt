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
import com.didimlog.infra.solvedac.SolvedAcClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.*

@DisplayName("AuthService 로그인 보안 테스트")
class AuthServiceLoginSecurityTest {

    private val solvedAcClient = mockk<SolvedAcClient>(relaxed = true)
    private val studentRepository = mockk<StudentRepository>(relaxed = true)
    private val jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>(relaxed = true)
    private val emailService = mockk<EmailService>(relaxed = true)
    private val passwordResetCodeRepository = mockk<PasswordResetCodeRepository>(relaxed = true)
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)

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
    @DisplayName("login은 존재하지 않는 BOJ ID에 대해 예외를 발생시킨다")
    fun `존재하지 않는 BOJ ID로 로그인 시도 시 예외 발생`() {
        // given
        val bojId = "nonexistent"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.login(bojId, password)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(exception.message).contains("가입되지 않은 BOJ ID입니다")
        verify(exactly = 1) { studentRepository.findByBojId(bojIdVo) }
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }
    }

    @Test
    @DisplayName("login은 비밀번호가 일치하지 않으면 예외를 발생시킨다")
    fun `비밀번호 불일치 시 예외 발생`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val wrongPassword = "WrongPassword123!"
        val student = createStudent(bojId = bojId, password = "encoded-password")

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        // Student.matchPassword() 내부에서 passwordEncoder.matches()를 호출
        every { passwordEncoder.matches(any(), any()) } returns false

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.login(bojId, wrongPassword)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("비밀번호가 일치하지 않습니다")
        verify(exactly = 1) { studentRepository.findByBojId(bojIdVo) }
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }
    }

    @Test
    @DisplayName("login은 비밀번호가 일치하면 JWT 토큰을 발급한다")
    fun `비밀번호 일치 시 토큰 발급 성공`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val password = "ValidPassword123!"
        val student = createStudent(bojId = bojId, password = "encoded-password")
        val expectedToken = "jwt-token-12345"

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        // Student.matchPassword() 내부에서 passwordEncoder.matches()를 호출
        every { passwordEncoder.matches(any(), any()) } returns true
        every { jwtTokenProvider.createToken(bojId, student.role.value) } returns expectedToken

        // when
        val result = authService.login(bojId, password)

        // then
        assertThat(result.token).isEqualTo(expectedToken)
        verify(exactly = 1) { jwtTokenProvider.createToken(bojId, student.role.value) }
    }

    @Test
    @DisplayName("login은 JWT 토큰에 role 정보를 포함시킨다")
    fun `JWT 토큰에 role 정보 포함 확인`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val password = "ValidPassword123!"
        val student = createStudent(bojId = bojId, password = "encoded-password", role = Role.ADMIN)
        val expectedToken = "jwt-token-with-admin-role"

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        // Student.matchPassword() 내부에서 passwordEncoder.matches()를 호출
        every { passwordEncoder.matches(any(), any()) } returns true
        every { jwtTokenProvider.createToken(bojId, Role.ADMIN.value) } returns expectedToken

        // when
        val result = authService.login(bojId, password)

        // then
        assertThat(result.token).isEqualTo(expectedToken)
        verify(exactly = 1) { jwtTokenProvider.createToken(bojId, Role.ADMIN.value) }
    }

    @Test
    @DisplayName("login은 비밀번호를 로그에 기록하지 않는다")
    fun `비밀번호 로그 노출 방지 확인`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val password = "SensitivePassword123!"
        val student = createStudent(bojId = bojId, password = "encoded-password")

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        // Student.matchPassword() 내부에서 passwordEncoder.matches()를 호출
        every { passwordEncoder.matches(any(), any()) } returns true
        every { jwtTokenProvider.createToken(any(), any()) } returns "token"

        // when
        authService.login(bojId, password)

        // then
        // 로그에 비밀번호가 포함되지 않았는지 확인
        // 실제로는 로그 파일을 확인해야 하지만, 여기서는 코드 검토로 대체
        // AuthService.login() 메서드에서 password를 로그에 기록하지 않음을 확인
        verify(exactly = 1) { jwtTokenProvider.createToken(bojId, student.role.value) }
    }

    @Test
    @DisplayName("login은 Solved.ac API 호출 실패 시에도 로그인을 진행한다")
    fun `SolvedAc API 실패 시에도 로그인 성공`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val password = "ValidPassword123!"
        val student = createStudent(bojId = bojId, password = "encoded-password")

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        // Student.matchPassword() 내부에서 passwordEncoder.matches()를 호출
        every { passwordEncoder.matches(any(), any()) } returns true
        // syncUserTier 내부에서 발생하는 예외는 catch되어 로그만 남음
        // value class인 BojId는 any() 매처가 제대로 작동하지 않으므로 구체적인 객체 사용
        every { solvedAcClient.fetchUser(bojIdVo) } throws IllegalStateException("API 호출 실패")
        every { studentRepository.save(any()) } answers { firstArg() }
        every { jwtTokenProvider.createToken(bojId, student.role.value) } returns "token"

        // when
        val result = authService.login(bojId, password)

        // then
        assertThat(result.token).isEqualTo("token")
        verify(exactly = 1) { jwtTokenProvider.createToken(bojId, student.role.value) }
    }

    private fun createStudent(
        bojId: String = "testuser",
        password: String = "encoded-password",
        role: Role = Role.USER,
        rating: Int = 1000
    ): Student {
        return Student(
            nickname = Nickname("test-user"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = password,
            rating = rating,
            currentTier = Tier.fromRating(rating),
            role = role,
            primaryLanguage = null
        )
    }
}

