package com.didimlog.application.member

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.DuplicateNicknameException
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

@DisplayName("MemberService 테스트")
class MemberServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val memberService = MemberService(studentRepository)

    @Test
    @DisplayName("닉네임이 유효하지 않으면 사용 불가(false)를 반환한다")
    fun `닉네임 사용 가능 여부 - 유효성 실패`() {
        val invalidNickname = "ㄱㄱ"

        val result = memberService.isNicknameAvailable(invalidNickname)

        assertThat(result).isFalse()
        verify { studentRepository wasNot Called }
    }

    @Test
    @DisplayName("닉네임이 유효하고 중복이 아니면 사용 가능(true)를 반환한다")
    fun `닉네임 사용 가능 여부 - 사용 가능`() {
        val nickname = "user_01"
        every { studentRepository.existsByNickname(Nickname(nickname)) } returns false

        val result = memberService.isNicknameAvailable(nickname)

        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("내 닉네임 변경 시 다른 사용자가 이미 사용 중이면 DuplicateNicknameException이 발생한다")
    fun `내 닉네임 변경 - 중복`() {
        val memberId = "me"
        val nickname = "user_01"

        val me = Student(
            id = memberId,
            nickname = Nickname("me01"),
            provider = Provider.BOJ,
            providerId = "me01",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val other = Student(
            id = "other",
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = "other",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById(memberId) } returns Optional.of(me)
        every { studentRepository.findByNickname(Nickname(nickname)) } returns Optional.of(other)

        assertThatThrownBy { memberService.updateMyNickname(memberId, nickname) }
            .isInstanceOf(DuplicateNicknameException::class.java)
            .hasMessageContaining("이미 사용 중인 닉네임입니다")
    }
}


