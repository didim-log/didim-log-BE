package com.didimlog.application.student

import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@DisplayName("StudentService 테스트")
class StudentServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val solvedAcClient: com.didimlog.infra.solvedac.SolvedAcClient = mockk(relaxed = true)
    private val refreshTokenService: RefreshTokenService = mockk(relaxed = true)
    private val accountDeletionService: AccountDeletionService = mockk(relaxed = true)

    private val studentService = StudentService(
        studentRepository,
        passwordEncoder,
        solvedAcClient,
        refreshTokenService,
        ImmediateCredentialSessionCoordinator(),
        accountDeletionService
    )

    @Test
    @DisplayName("닉네임만 변경하는 경우 성공한다")
    fun `닉네임만 변경 성공`() {
        // given
        val bojId = "testuser"
        val oldNickname = Nickname("oldNickname")
        val newNickname = "newNickname"
        val student = Student(
            id = "student-id",
            nickname = oldNickname,
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { studentRepository.existsByNickname(Nickname(newNickname)) } returns false
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val result = studentService.updateProfile(
            studentId = "student-id",
            nickname = newNickname,
            currentPassword = null,
            newPassword = null
        )

        // then
        assertThat(result.nickname.value).isEqualTo(newNickname)
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 1) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("비밀번호를 정상적으로 변경하는 경우 성공한다")
    fun `비밀번호 변경 성공`() {
        // given
        val bojId = "testuser"
        val currentPassword = "currentPassword123"
        val newPassword = "newPassword123!"
        val encodedCurrentPassword = "encoded-current-password"
        val encodedNewPassword = "encoded-new-password"

        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = encodedCurrentPassword,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { passwordEncoder.matches(currentPassword, encodedCurrentPassword) } returns true
        every { passwordEncoder.matches(newPassword, encodedCurrentPassword) } returns false
        every { passwordEncoder.encode(newPassword) } returns encodedNewPassword
        every {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = encodedNewPassword,
                primaryLanguage = null,
                expectedCredentialVersion = 0
            )
        } returns student.copy(
            password = encodedNewPassword,
            credentialVersion = 1
        )

        // when
        val result = studentService.updateProfile(
            studentId = "student-id",
            nickname = null,
            currentPassword = currentPassword,
            newPassword = newPassword
        )

        // then
        assertThat(result.password).isEqualTo(encodedNewPassword)
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent("student-id") }
        verify(exactly = 1) {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = encodedNewPassword,
                primaryLanguage = null,
                expectedCredentialVersion = 0
            )
        }
        assertThat(result.credentialVersion).isEqualTo(1)
        verifyOrder {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = encodedNewPassword,
                primaryLanguage = null,
                expectedCredentialVersion = 0
            )
            refreshTokenService.revokeAllForStudent("student-id")
        }
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("비밀번호 부분 갱신 뒤 Refresh Token 폐기 실패를 전파한다")
    fun `비밀번호 변경 실패 - 부분 갱신 후 세션 폐기 실패`() {
        val bojId = "testuser"
        val currentPassword = "currentPassword123"
        val newPassword = "newPassword123!"
        val encodedCurrentPassword = "encoded-current-password"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = encodedCurrentPassword,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { passwordEncoder.matches(currentPassword, encodedCurrentPassword) } returns true
        every { passwordEncoder.matches(newPassword, encodedCurrentPassword) } returns false
        every { passwordEncoder.encode(newPassword) } returns "encoded-new-password"
        every {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = "encoded-new-password",
                primaryLanguage = null,
                expectedCredentialVersion = 0
            )
        } returns student.copy(
            password = "encoded-new-password",
            credentialVersion = 1
        )
        every {
            refreshTokenService.revokeAllForStudent("student-id")
        } throws IllegalStateException("Redis unavailable")

        assertThrows<IllegalStateException> {
            studentService.updateProfile(
                studentId = "student-id",
                nickname = null,
                currentPassword = currentPassword,
                newPassword = newPassword
            )
        }

        verify(exactly = 1) { refreshTokenService.revokeAllForStudent("student-id") }
        verify(exactly = 1) {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = "encoded-new-password",
                primaryLanguage = null,
                expectedCredentialVersion = 0
            )
        }
        verifyOrder {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = "encoded-new-password",
                primaryLanguage = null,
                expectedCredentialVersion = 0
            )
            refreshTokenService.revokeAllForStudent("student-id")
        }
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("자격 증명 버전이 바뀌면 비밀번호 변경을 충돌로 중단한다")
    fun `비밀번호 변경 실패 - 자격 증명 버전 충돌`() {
        val bojId = "testuser"
        val currentPassword = "currentPassword123"
        val newPassword = "newPassword123!"
        val encodedCurrentPassword = "encoded-current-password"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = encodedCurrentPassword,
            credentialVersion = 2,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { passwordEncoder.matches(currentPassword, encodedCurrentPassword) } returns true
        every { passwordEncoder.matches(newPassword, encodedCurrentPassword) } returns false
        every { passwordEncoder.encode(newPassword) } returns "encoded-new-password"
        every {
            studentRepository.updateProfileFieldsById(
                studentId = "student-id",
                nickname = null,
                encodedPassword = "encoded-new-password",
                primaryLanguage = null,
                expectedCredentialVersion = 2
            )
        } returns null

        val exception = assertThrows<BusinessException> {
            studentService.updateProfile(
                studentId = "student-id",
                nickname = null,
                currentPassword = currentPassword,
                newPassword = newPassword
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("잠금 대기 중 BOJ ID가 바뀌어도 같은 학생 ID의 비밀번호를 변경한다")
    fun `BOJ ID 변경 뒤 학생 ID로 비밀번호 변경`() {
        val oldBojId = "testuser"
        val currentPassword = "currentPassword123"
        val newPassword = "newPassword123!"
        val encodedCurrentPassword = "encoded-current-password"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = oldBojId,
            bojId = BojId(oldBojId),
            password = encodedCurrentPassword,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val renamedStudent = student.copy(bojId = BojId("renamed_user"))

        every {
            studentRepository.findById("student-id")
        } returnsMany listOf(Optional.of(student), Optional.of(renamedStudent))
        every { passwordEncoder.matches(currentPassword, encodedCurrentPassword) } returns true
        every { passwordEncoder.matches(newPassword, encodedCurrentPassword) } returns false
        every { passwordEncoder.encode(newPassword) } returns "encoded-new-password"

        every {
            studentRepository.updateProfileFieldsById(
                "student-id",
                null,
                "encoded-new-password",
                null,
                0
            )
        } returns renamedStudent.copy(password = "encoded-new-password", credentialVersion = 1)

        val result = studentService.updateProfile(
            studentId = "student-id",
            nickname = null,
            currentPassword = currentPassword,
            newPassword = newPassword
        )

        assertThat(result.bojId).isEqualTo(BojId("renamed_user"))
        assertThat(result.credentialVersion).isEqualTo(1)
        verify(exactly = 1) {
            studentRepository.updateProfileFieldsById(
                "student-id",
                null,
                "encoded-new-password",
                null,
                0
            )
        }
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent("student-id") }
    }

    @Test
    @DisplayName("이미 존재하는 닉네임으로 변경 시도 시 예외가 발생한다")
    fun `중복 닉네임 변경 시 예외 발생`() {
        // given
        val bojId = "testuser"
        val oldNickname = Nickname("oldNickname")
        val duplicateNickname = "dupNick12"
        val student = Student(
            id = "student-id",
            nickname = oldNickname,
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { studentRepository.existsByNickname(Nickname(duplicateNickname)) } returns true

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                studentId = "student-id",
                nickname = duplicateNickname,
                currentPassword = null,
                newPassword = null
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.DUPLICATE_NICKNAME })
            .hasMessageContaining("이미 사용 중인 닉네임입니다")
    }

    @Test
    @DisplayName("현재 비밀번호가 틀려서 비밀번호 변경 불가 시 예외가 발생한다")
    fun `비밀번호 불일치 시 예외 발생`() {
        // given
        val bojId = "testuser"
        val wrongCurrentPassword = "wrongPassword123"
        val newPassword = "newPassword123!"
        val encodedCurrentPassword = "encoded-current-password"

        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = encodedCurrentPassword,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { passwordEncoder.matches(wrongCurrentPassword, encodedCurrentPassword) } returns false

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                studentId = "student-id",
                nickname = null,
                currentPassword = wrongCurrentPassword,
                newPassword = newPassword
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.PASSWORD_MISMATCH })
            .hasMessageContaining("현재 비밀번호가 일치하지 않습니다")
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
    }

    @Test
    @DisplayName("새 비밀번호만 입력하고 현재 비밀번호를 입력하지 않으면 예외가 발생한다")
    fun `현재 비밀번호 없이 새 비밀번호 변경 시도 시 예외 발생`() {
        // given
        val bojId = "testuser"
        val newPassword = "newPassword123!"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 0,
            currentTier = Tier.BRONZE,
            role = Role.USER,
            termsAgreed = true,
            solutions = com.didimlog.domain.Solutions(),
            consecutiveSolveDays = 0,
            lastSolvedAt = null
        )

        every { studentRepository.findById("student-id") } returns Optional.of(student)

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                studentId = "student-id",
                nickname = null,
                currentPassword = null,
                newPassword = newPassword
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.COMMON_INVALID_INPUT })
            .hasMessageContaining("현재 비밀번호를 입력해야 합니다")
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
    }

    @Test
    @DisplayName("학생을 찾을 수 없으면 예외가 발생한다")
    fun `학생 없음 예외 발생`() {
        // given
        val studentId = "nonexistent"
        every { studentRepository.findById(studentId) } returns Optional.empty()

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                studentId = studentId,
                nickname = "newNickname",
                currentPassword = null,
                newPassword = null
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.STUDENT_NOT_FOUND })
    }

    @Test
    @DisplayName("회원 탈퇴를 공통 계정 삭제 흐름에 위임한다")
    fun `회원 탈퇴 공통 흐름 위임`() {
        val studentId = "student-id"

        studentService.withdraw(studentId)

        verify(exactly = 1) { accountDeletionService.deleteAccount(studentId) }
    }

    @Test
    @DisplayName("BOJ 프로필 동기화 시 변경이 없으면 기존 학생을 반환하고 저장하지 않는다")
    fun `syncBojProfile 변경 없음`() {
        // given
        val bojId = "testuser"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 500,
            solvedAcTierLevel = SolvedAcTierLevel.fromRating(500),
            currentTier = Tier.SILVER,
            role = Role.USER
        )
        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { solvedAcClient.fetchUser(BojId(bojId)) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 500,
            tier = 9
        )

        // when
        val result = studentService.syncBojProfile("student-id")

        // then
        assertThat(result).isEqualTo(student)
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
        verify(exactly = 0) {
            studentRepository.updateSolvedAcProfileById(
                "student-id",
                BojId(bojId),
                500,
                SolvedAcTierLevel.fromRating(500),
                Tier.SILVER
            )
        }
    }

    @Test
    @DisplayName("BOJ 프로필 동기화 시 변경이 있으면 학생 정보를 갱신해 저장한다")
    fun `syncBojProfile 변경 있음`() {
        // given
        val bojId = "testuser"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 500,
            solvedAcTierLevel = SolvedAcTierLevel.fromRating(500),
            currentTier = Tier.SILVER,
            role = Role.USER
        )
        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { solvedAcClient.fetchUser(BojId(bojId)) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 1200,
            tier = 13
        )
        val expectedStudent = student.updateSolvedAcProfile(
            newRating = 1200,
            newTierLevel = SolvedAcTierLevel.fromRating(1200)
        )
        every {
            studentRepository.updateSolvedAcProfileById(
                "student-id",
                BojId(bojId),
                1200,
                SolvedAcTierLevel.fromRating(1200),
                Tier.GOLD
            )
        } returns expectedStudent

        // when
        val updated = studentService.syncBojProfile("student-id")

        // then
        assertThat(updated.rating).isEqualTo(1200)
        assertThat(updated.solvedAcTierLevel).isEqualTo(SolvedAcTierLevel.fromRating(1200))
        verify(exactly = 1) {
            studentRepository.updateSolvedAcProfileById(
                "student-id",
                BojId(bojId),
                1200,
                SolvedAcTierLevel.fromRating(1200),
                Tier.GOLD
            )
        }
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("BOJ 프로필 갱신 중 학생이 삭제되면 찾을 수 없음 예외를 발생시킨다")
    fun `syncBojProfile 갱신 대상 삭제`() {
        val bojId = "testuser"
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 500,
            currentTier = Tier.SILVER,
            role = Role.USER
        )
        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { solvedAcClient.fetchUser(BojId(bojId)) } returns SolvedAcUserResponse(
            handle = bojId,
            rating = 1200,
            tier = 13
        )
        every {
            studentRepository.updateSolvedAcProfileById(
                "student-id",
                BojId(bojId),
                1200,
                SolvedAcTierLevel.fromRating(1200),
                Tier.GOLD
            )
        } returns null

        val exception = assertThrows<BusinessException> {
            studentService.syncBojProfile("student-id")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
    }

    @Test
    @DisplayName("BOJ ID가 연결되지 않은 학생은 프로필을 동기화할 수 없다")
    fun `syncBojProfile BOJ ID 미연동`() {
        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.GITHUB,
            providerId = "github-id",
            bojId = null,
            password = "encoded-password",
            rating = 500,
            currentTier = Tier.SILVER,
            role = Role.USER
        )
        every { studentRepository.findById("student-id") } returns Optional.of(student)

        val exception = assertThrows<BusinessException> {
            studentService.syncBojProfile("student-id")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
    }
}
