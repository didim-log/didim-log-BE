package com.didimlog.application.auth

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@DisplayName("AuthService 테스트")
class AuthServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()

    private val authService = AuthService(
        solvedAcClient,
        studentRepository,
        jwtTokenProvider,
        passwordEncoder
    )

    @Test
    @DisplayName("중복된 BOJ ID로 회원가입 시 예외가 발생한다")
    fun `중복된 BOJ ID 회원가입 시 예외 발생`() {
        // given
        val bojId = "duplicate"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)
        val existingStudent = Student(
            id = "student1",
            nickname = Nickname("existing"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = bojIdVo,
            password = "encoded",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(
            handle = "duplicate",
            rating = 100
        )
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(existingStudent)

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.signup(bojId, password)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("이미 가입된 BOJ ID입니다")
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("비밀번호 규칙에 맞지 않는 비밀번호로 회원가입 시 예외가 발생한다")
    fun `비밀번호 규칙 위반 시 예외 발생`() {
        // given
        val bojId = "testuser"
        val invalidPassword = "short" // 너무 짧은 비밀번호

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(invalidPassword) } throws com.didimlog.global.exception.InvalidPasswordException(
            "비밀번호 규칙 위반"
        )

        // when & then
        assertThatThrownBy {
            authService.signup(bojId, invalidPassword)
        }.isInstanceOf(com.didimlog.global.exception.InvalidPasswordException::class.java)
            .isNotNull
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("유효하지 않은 BOJ ID로 회원가입 시 예외가 발생한다")
    fun `유효하지 않은 BOJ ID 회원가입 시 예외 발생`() {
        // given
        val bojId = "invalid"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(bojIdVo) } throws IllegalStateException("유효하지 않은 BOJ ID")

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.signup(bojId, password)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("유효하지 않은 BOJ ID입니다")
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 로그인 시 예외가 발생한다")
    fun `비밀번호 불일치 시 로그인 예외 발생`() {
        // given
        val bojId = "testuser"
        val wrongPassword = "WrongPassword123!"
        val bojIdVo = BojId(bojId)
        val student = Student(
            id = "student1",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = bojIdVo,
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        every { student.matchPassword(wrongPassword, passwordEncoder) } returns false

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.login(bojId, wrongPassword)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("비밀번호가 일치하지 않습니다")
    }

    @Test
    @DisplayName("가입되지 않은 BOJ ID로 로그인 시 예외가 발생한다")
    fun `가입되지 않은 BOJ ID 로그인 시 예외 발생`() {
        // given
        val bojId = "nonexistent"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.login(bojId, password)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(exception.message).contains("가입되지 않은 BOJ ID입니다")
    }
}
