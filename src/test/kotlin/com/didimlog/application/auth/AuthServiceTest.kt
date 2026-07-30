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
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@DisplayName("AuthService 테스트")
class AuthServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val emailService: com.didimlog.infra.email.EmailService = mockk(relaxed = true)
    private val passwordResetCodeRepository: com.didimlog.domain.repository.PasswordResetCodeRepository = mockk(relaxed = true)
    private val passwordResetCodeGenerator: PasswordResetCodeGenerator = mockk(relaxed = true)
    private val refreshTokenService: RefreshTokenService = mockk(relaxed = true)
    private val bojOwnershipVerificationService =
        mockk<com.didimlog.application.auth.boj.BojOwnershipVerificationService>(relaxed = true)
    private val credentialSessionCoordinator = RecordingCredentialSessionCoordinator()

    private val authService = AuthService(
        solvedAcClient,
        studentRepository,
        jwtTokenProvider,
        passwordEncoder,
        emailService,
        passwordResetCodeRepository,
        passwordResetCodeGenerator,
        refreshTokenService,
        bojOwnershipVerificationService,
        credentialSessionCoordinator
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
            rating = 100,
            tier = 3
        )
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(existingStudent)

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.signup(bojId, password, "test@example.com", "verification-session")
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
            authService.signup(bojId, invalidPassword, "test@example.com", "verification-session")
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
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail("test@example.com") } returns Optional.empty()
        every { solvedAcClient.fetchUser(bojIdVo) } throws IllegalStateException("유효하지 않은 BOJ ID")

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.signup(bojId, password, "test@example.com", "verification-session")
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

    @Test
    @DisplayName("정상적인 회원가입이 성공한다")
    fun `정상적인 회원가입 성공`() {
        // given
        val bojId = "newuser"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)
        val userResponse = SolvedAcUserResponse(handle = "newuser", rating = 100, tier = 3)
        val encodedPassword = "encoded-password"
        val token = "jwt-token"

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(bojIdVo) } returns userResponse
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail("test@example.com") } returns Optional.empty()
        every { passwordEncoder.encode(password) } returns encodedPassword
        every {
            jwtTokenProvider.createToken(bojId, "student1", 0, Role.USER.value)
        } returns token
        every { refreshTokenService.generateAndSave(any<Student>()) } returns "refresh-token"
        val savedStudent = slot<Student>()
        every {
            studentRepository.save(capture(savedStudent))
        } answers { firstArg<Student>().copy(id = "student1") }
        every {
            studentRepository.findById("student1")
        } answers {
            Optional.of(savedStudent.captured.copy(id = "student1"))
        }

        // when
        val result = authService.signup(bojId, password, "test@example.com", "verification-session")

        // then
        assertThat(result.token).isEqualTo(token)
        assertThat(result.rating).isEqualTo(100)
        assertThat(result.tier).isEqualTo(Tier.BRONZE)
        assertThat(result.tierLevel).isEqualTo(3)
        assertThat(savedStudent.captured.isVerified).isTrue()
        assertThat(credentialSessionCoordinator.strictStudentIds).containsExactly("student1")
        verify(exactly = 1) {
            bojOwnershipVerificationService.consumeVerifiedBojId("verification-session", bojId)
        }
        verify(exactly = 1) { studentRepository.save(any()) }
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("회원가입 후 학생 잠금에 실패하면 토큰을 발급하지 않는다")
    fun `회원가입 후 학생 잠금 실패 시 토큰 발급 중단`() {
        val bojId = "lockeduser"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)
        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail("locked@example.com") } returns Optional.empty()
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 100,
            tier = 3
        )
        every { passwordEncoder.encode(password) } returns "encoded-password"
        every {
            studentRepository.save(any<Student>())
        } answers { firstArg<Student>().copy(id = "locked-student") }
        val rejectingService = AuthService(
            solvedAcClient,
            studentRepository,
            jwtTokenProvider,
            passwordEncoder,
            emailService,
            passwordResetCodeRepository,
            passwordResetCodeGenerator,
            refreshTokenService,
            bojOwnershipVerificationService,
            RejectingCredentialSessionCoordinator()
        )

        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            rejectingService.signup(
                bojId,
                password,
                "locked@example.com",
                "verification-session"
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshTokenService.generateAndSave(any<Student>()) }
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("소셜 가입 완료는 저장된 학생을 잠금 안에서 다시 읽고 토큰을 발급한다")
    fun `소셜 가입 완료 후 최신 학생으로 토큰 발급`() {
        val email = "social@example.com"
        val providerId = "github-provider"
        val bojId = "social_boj"
        val bojIdVo = BojId(bojId)
        val existingStudent = Student(
            id = "social-student",
            nickname = Nickname("beforeuser"),
            provider = Provider.GITHUB,
            providerId = providerId,
            email = email,
            bojId = null,
            password = null,
            currentTier = Tier.BRONZE,
            role = Role.GUEST
        )
        val savedStudent = slot<Student>()
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 1_500,
            tier = 13
        )
        every {
            studentRepository.findByProviderAndProviderId(Provider.GITHUB, providerId)
        } returns Optional.of(existingStudent)
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail(email) } returns Optional.of(existingStudent)
        every { studentRepository.existsByNickname(Nickname("social-user")) } returns false
        every {
            studentRepository.save(capture(savedStudent))
        } answers { firstArg<Student>() }
        every {
            studentRepository.findById("social-student")
        } answers { Optional.of(savedStudent.captured) }
        every {
            jwtTokenProvider.createToken(bojId, "social-student", 0, Role.USER.value)
        } returns "social-token"
        every { refreshTokenService.generateAndSave(any<Student>()) } returns "social-refresh"

        val result = authService.finalizeSignup(
            email = email,
            provider = "github",
            providerId = providerId,
            nickname = "social-user",
            bojId = bojId,
            termsAgreed = true
        )

        assertThat(result.token).isEqualTo("social-token")
        assertThat(result.refreshToken).isEqualTo("social-refresh")
        assertThat(credentialSessionCoordinator.strictStudentIds)
            .containsExactly("social-student")
        verify(exactly = 1) {
            refreshTokenService.generateAndSave(
                match { it.id == "social-student" && it.role == Role.USER }
            )
        }
    }

    @Test
    @DisplayName("계정 저장이 실패해도 소비한 BOJ 인증 세션은 복구하지 않는다")
    fun `회원가입 저장 실패 시 인증 세션 소비 유지`() {
        val bojId = "newuser"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 100,
            tier = 3
        )
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail("test@example.com") } returns Optional.empty()
        every { passwordEncoder.encode(password) } returns "encoded-password"
        every { studentRepository.save(any()) } throws IllegalStateException("MongoDB unavailable")

        assertThatThrownBy {
            authService.signup(bojId, password, "test@example.com", "verification-session")
        }
            .isInstanceOf(IllegalStateException::class.java)

        verifyOrder {
            bojOwnershipVerificationService.consumeVerifiedBojId("verification-session", bojId)
            studentRepository.save(any())
        }
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("동시 가입 중 이메일 유일성 충돌은 이메일 중복으로 안내한다")
    fun `회원가입 이메일 유일성 충돌 분류`() {
        val bojId = "newuser"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 100,
            tier = 3
        )
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail("test@example.com") } returns Optional.empty()
        every { passwordEncoder.encode(password) } returns "encoded-password"
        every { studentRepository.save(any()) } throws DuplicateKeyException(
            "E11000 duplicate key error index: uniq_student_email"
        )

        assertThatThrownBy {
            authService.signup(bojId, password, "test@example.com", "verification-session")
        }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("이미 사용 중인 이메일입니다")

        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("동시 가입 중 닉네임 유일성 충돌은 닉네임 중복으로 안내한다")
    fun `회원가입 닉네임 유일성 충돌 분류`() {
        val bojId = "newuser"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)

        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 100,
            tier = 3
        )
        every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
        every { studentRepository.findByEmail("test@example.com") } returns Optional.empty()
        every { passwordEncoder.encode(password) } returns "encoded-password"
        every { studentRepository.save(any()) } throws DuplicateKeyException(
            "E11000 duplicate key error index: uniq_student_nickname"
        )

        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            authService.signup(bojId, password, "test@example.com", "verification-session")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.DUPLICATE_NICKNAME)

        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("정상적인 로그인이 성공한다")
    fun `정상적인 로그인 성공`() {
        // given
        val bojId = "testuser"
        val password = "ValidPassword123!"
        val bojIdVo = BojId(bojId)
        val student = Student(
            id = "student1",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = bojIdVo,
            password = "encoded-password",
            rating = 100,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val token = "jwt-token"

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        every { studentRepository.findById("student1") } returns Optional.of(student)
        every { student.matchPassword(password, passwordEncoder) } returns true
        every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(handle = "testuser", rating = 100, tier = 3)
        every {
            jwtTokenProvider.createToken(bojId, "student1", student.credentialVersion, Role.USER.value)
        } returns token

        // when
        val result = authService.login(bojId, password)

        // then
        assertThat(result.token).isEqualTo(token)
        assertThat(result.rating).isEqualTo(100)
        assertThat(result.tier).isEqualTo(Tier.BRONZE)
        assertThat(result.tierLevel).isEqualTo(3)
        verify(exactly = 0) {
            studentRepository.updateSolvedAcProfileById(
                "student1",
                bojIdVo,
                100,
                student.solvedAcTierLevel,
                Tier.BRONZE
            )
        }
        verify(exactly = 0) { studentRepository.save(any()) }
    }

    private class RecordingCredentialSessionCoordinator : CredentialSessionCoordinator {
        val strictStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T = action()

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            strictStudentIds += studentId
            return action()
        }
    }

    private class RejectingCredentialSessionCoordinator : CredentialSessionCoordinator {
        override fun <T> execute(studentId: String, action: () -> T): T = action()

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            throw BusinessException(ErrorCode.SESSION_STATE_CONFLICT)
        }
    }
}
