package com.didimlog.application.student

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@DisplayName("StudentService 테스트")
class StudentServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk(relaxed = true)
    private val feedbackRepository: FeedbackRepository = mockk(relaxed = true)
    private val passwordEncoder: PasswordEncoder = mockk()

    private val studentService = StudentService(
        studentRepository,
        retrospectiveRepository,
        feedbackRepository,
        passwordEncoder
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

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { studentRepository.existsByNickname(Nickname(newNickname)) } returns false
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val result = studentService.updateProfile(
            bojId = bojId,
            nickname = newNickname,
            currentPassword = null,
            newPassword = null
        )

        // then
        assertThat(result.nickname.value).isEqualTo(newNickname)
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

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { passwordEncoder.matches(currentPassword, encodedCurrentPassword) } returns true
        every { passwordEncoder.matches(newPassword, encodedCurrentPassword) } returns false
        every { passwordEncoder.encode(newPassword) } returns encodedNewPassword
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val result = studentService.updateProfile(
            bojId = bojId,
            nickname = null,
            currentPassword = currentPassword,
            newPassword = newPassword
        )

        // then
        assertThat(result.password).isEqualTo(encodedNewPassword)
        verify(exactly = 1) { studentRepository.save(any<Student>()) }
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

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { studentRepository.existsByNickname(Nickname(duplicateNickname)) } returns true

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                bojId = bojId,
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

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { passwordEncoder.matches(wrongCurrentPassword, encodedCurrentPassword) } returns false

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                bojId = bojId,
                nickname = null,
                currentPassword = wrongCurrentPassword,
                newPassword = newPassword
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.PASSWORD_MISMATCH })
            .hasMessageContaining("현재 비밀번호가 일치하지 않습니다")
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

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                bojId = bojId,
                nickname = null,
                currentPassword = null,
                newPassword = newPassword
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.COMMON_INVALID_INPUT })
            .hasMessageContaining("현재 비밀번호를 입력해야 합니다")
    }

    @Test
    @DisplayName("학생을 찾을 수 없으면 예외가 발생한다")
    fun `학생 없음 예외 발생`() {
        // given
        val bojId = "nonexistent"
        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.empty()

        // when & then
        assertThatThrownBy {
            studentService.updateProfile(
                bojId = bojId,
                nickname = "newNickname",
                currentPassword = null,
                newPassword = null
            )
        }
            .isInstanceOf(BusinessException::class.java)
            .matches({ (it as BusinessException).errorCode == ErrorCode.STUDENT_NOT_FOUND })
    }

    @Test
    @DisplayName("회원 탈퇴 시 학생과 연관 데이터(회고/피드백)를 Hard Delete 한다")
    fun `회원 탈퇴 Hard Delete 성공`() {
        // given
        val bojId = "testuser"
        val studentId = "student-id"
        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { studentRepository.delete(student) } returns Unit
        every { retrospectiveRepository.deleteAllByStudentId(studentId) } returns Unit
        every { feedbackRepository.deleteAllByWriterId(studentId) } returns Unit

        // when
        studentService.withdraw(bojId)

        // then
        verify(exactly = 1) { retrospectiveRepository.deleteAllByStudentId(studentId) }
        verify(exactly = 1) { feedbackRepository.deleteAllByWriterId(studentId) }
        verify(exactly = 1) { studentRepository.delete(student) }
    }
}

