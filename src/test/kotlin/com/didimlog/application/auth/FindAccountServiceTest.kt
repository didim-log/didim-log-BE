package com.didimlog.application.auth

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
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
import java.util.Optional

@DisplayName("FindAccountService 테스트")
class FindAccountServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val service = FindAccountService(studentRepository)

    @Test
    @DisplayName("이메일이 존재하면 provider와 안내 메시지를 반환한다")
    fun `이메일 존재 시 provider 반환`() {
        // given
        val email = "test@example.com"
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.GITHUB,
            providerId = "123",
            email = email,
            currentTier = Tier.BRONZE
        )
        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when
        val result = service.findAccount(email)

        // then
        assertThat(result.provider).isEqualTo("GITHUB")
        assertThat(result.message).contains("GITHUB")
        verify(exactly = 1) { studentRepository.findByEmail(email) }
    }

    @Test
    @DisplayName("이메일이 존재하지 않으면 STUDENT_NOT_FOUND 예외가 발생한다")
    fun `이메일 없으면 예외`() {
        // given
        val email = "unknown@example.com"
        every { studentRepository.findByEmail(email) } returns Optional.empty()

        // when & then
        assertThatThrownBy { service.findAccount(email) }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("가입 정보가 없습니다")

        verify(exactly = 1) { studentRepository.findByEmail(email) }
    }
}

















