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
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder

@DisplayName("슈퍼 관리자 생성 테스트")
class SuperAdminTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val emailService: EmailService = mockk()
    private val passwordResetCodeRepository: PasswordResetCodeRepository = mockk()
    private val passwordResetCodeGenerator: PasswordResetCodeGenerator = mockk(relaxed = true)
    private val refreshTokenService: RefreshTokenService = mockk()
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
    @DisplayName("올바른 adminKey로 슈퍼 관리자 계정을 생성하면 ADMIN role이 설정된다")
    fun `올바른 adminKey로 슈퍼 관리자 생성 성공`() {
        // given
        val bojId = "admin123"
        val password = "password123!"
        val email = "admin@example.com"
        val adminKey = "valid-admin-key"
        val encodedPassword = "encoded-password"
        val rating = 1500
        val tier = Tier.fromRating(rating) // 1500점은 GOLD 티어

        val userResponse = SolvedAcUserResponse(
            handle = bojId,
            rating = rating,
            tier = 15
        )

        mockkObject(com.didimlog.global.util.PasswordValidator)
        every { com.didimlog.global.util.PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(BojId(bojId)) } returns userResponse
        every { studentRepository.findByBojId(BojId(bojId)) } returns java.util.Optional.empty()
        every { studentRepository.findByEmail(email) } returns java.util.Optional.empty()
        every { passwordEncoder.encode(password) } returns encodedPassword
        val savedStudent = slot<Student>()
        every {
            studentRepository.save(capture(savedStudent))
        } answers { firstArg<Student>().copy(id = "admin-student-id") }
        every {
            studentRepository.findById("admin-student-id")
        } answers {
            java.util.Optional.of(savedStudent.captured.copy(id = "admin-student-id"))
        }
        every {
            jwtTokenProvider.createToken(bojId, "admin-student-id", 0, Role.ADMIN.value)
        } returns "admin-token"
        every { refreshTokenService.generateAndSave(any<Student>()) } returns "refresh-token"

        // when
        val result = authService.createSuperAdmin(bojId, password, email, adminKey)

        // then
        assertThat(result.token).isEqualTo("admin-token")
        assertThat(result.rating).isEqualTo(rating)
        assertThat(result.tier).isEqualTo(tier)
        assertThat(credentialSessionCoordinator.strictStudentIds)
            .containsExactly("admin-student-id")

        verify(exactly = 1) {
            studentRepository.save(
                match<Student> { it.role == Role.ADMIN }
            )
        }
        verify(exactly = 0) {
            bojOwnershipVerificationService.consumeVerifiedBojId(any(), any())
        }
        unmockkObject(com.didimlog.global.util.PasswordValidator)
    }

    @Test
    @DisplayName("이미 가입된 BOJ ID로 슈퍼 관리자를 생성하려고 하면 예외가 발생한다")
    fun `이미 가입된 BOJ ID로 슈퍼 관리자 생성 실패`() {
        // given
        val bojId = "existing123"
        val password = "password123!"
        val email = "existing@example.com"
        val adminKey = "valid-admin-key"

        val existingStudent = Student(
            nickname = Nickname("existing"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        mockkObject(com.didimlog.global.util.PasswordValidator)
        every { com.didimlog.global.util.PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(BojId(bojId)) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 100,
            tier = 3
        )
        every { studentRepository.findByBojId(BojId(bojId)) } returns java.util.Optional.of(existingStudent)

        // when & then
        assertThatThrownBy {
            authService.createSuperAdmin(bojId, password, email, adminKey)
        }.isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("이미 가입된 BOJ ID입니다")
        
        unmockkObject(com.didimlog.global.util.PasswordValidator)
    }

    private class RecordingCredentialSessionCoordinator : CredentialSessionCoordinator {
        val strictStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T = action()

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            strictStudentIds += studentId
            return action()
        }
    }
}
