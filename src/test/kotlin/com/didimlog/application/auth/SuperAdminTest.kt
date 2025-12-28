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

@DisplayName("슈퍼 관리자 생성 테스트")
class SuperAdminTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val emailService: EmailService = mockk()
    private val passwordResetCodeRepository: PasswordResetCodeRepository = mockk()

    private val authService = AuthService(
        solvedAcClient,
        studentRepository,
        jwtTokenProvider,
        passwordEncoder,
        emailService,
        passwordResetCodeRepository
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
            rating = rating
        )

        mockkObject(com.didimlog.global.util.PasswordValidator)
        every { com.didimlog.global.util.PasswordValidator.validate(password) } returns Unit
        every { solvedAcClient.fetchUser(BojId(bojId)) } returns userResponse
        every { studentRepository.findByBojId(BojId(bojId)) } returns java.util.Optional.empty()
        every { studentRepository.findByEmail(email) } returns java.util.Optional.empty()
        every { passwordEncoder.encode(password) } returns encodedPassword
        every { studentRepository.save(any<Student>()) } answers { firstArg() }
        every { jwtTokenProvider.createToken(bojId, Role.ADMIN.value) } returns "admin-token"

        // when
        val result = authService.createSuperAdmin(bojId, password, email, adminKey)

        // then
        assertThat(result.token).isEqualTo("admin-token")
        assertThat(result.rating).isEqualTo(rating)
        assertThat(result.tier).isEqualTo(tier)

        verify(exactly = 1) {
            studentRepository.save(
                match<Student> { it.role == Role.ADMIN }
            )
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
            rating = 100
        )
        every { studentRepository.findByBojId(BojId(bojId)) } returns java.util.Optional.of(existingStudent)

        // when & then
        assertThatThrownBy {
            authService.createSuperAdmin(bojId, password, email, adminKey)
        }.isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("이미 가입된 BOJ ID입니다")
        
        unmockkObject(com.didimlog.global.util.PasswordValidator)
    }
}
