package com.didimlog.application.member

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.DuplicateNicknameException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@DisplayName("AdminMemberService 테스트")
class AdminMemberServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val adminMemberService = AdminMemberService(studentRepository, passwordEncoder)

    @Test
    fun `닉네임과 비밀번호가 모두 null이면 저장하지 않는다`() {
        val memberId = "member-1"
        val member = student(memberId, "oldNick")
        every { studentRepository.findById(memberId) } returns Optional.of(member)

        adminMemberService.updateMember(memberId, nickname = null, password = null)

        verify(exactly = 0) { studentRepository.save(any()) }
    }

    @Test
    fun `닉네임 변경이 있으면 저장한다`() {
        val memberId = "member-1"
        val member = student(memberId, "oldNick")
        every { studentRepository.findById(memberId) } returns Optional.of(member)
        every { studentRepository.findByNickname(Nickname("newNick")) } returns Optional.empty()
        every { studentRepository.save(any()) } answers { firstArg() }

        adminMemberService.updateMember(memberId, nickname = "newNick", password = null)

        verify(exactly = 1) { studentRepository.save(match { it.nickname.value == "newNick" }) }
    }

    @Test
    fun `비밀번호 변경이 있으면 인코딩 후 저장한다`() {
        val memberId = "member-1"
        val member = student(memberId, "oldNick")
        every { studentRepository.findById(memberId) } returns Optional.of(member)
        every { passwordEncoder.encode("new-password") } returns "encoded-password"
        every { studentRepository.save(any()) } answers { firstArg() }

        adminMemberService.updateMember(memberId, nickname = null, password = "new-password")

        verify(exactly = 1) { passwordEncoder.encode("new-password") }
        verify(exactly = 1) { studentRepository.save(match { it.password == "encoded-password" }) }
    }

    @Test
    fun `다른 사용자가 같은 닉네임을 쓰면 예외가 발생한다`() {
        val memberId = "member-1"
        val member = student(memberId, "oldNick")
        val other = student("member-2", "dupNick")
        every { studentRepository.findById(memberId) } returns Optional.of(member)
        every { studentRepository.findByNickname(Nickname("dupNick")) } returns Optional.of(other)

        assertThatThrownBy {
            adminMemberService.updateMember(memberId, nickname = "dupNick", password = null)
        }.isInstanceOf(DuplicateNicknameException::class.java)
    }

    private fun student(id: String, nickname: String): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = "provider-$id",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }
}

