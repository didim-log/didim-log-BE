package com.didimlog.application.member

import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.DuplicateNicknameException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminMemberService(
    private val studentRepository: StudentRepository,
    private val passwordEncoder: PasswordEncoder
) {

    @Transactional
    fun updateMember(memberId: String, nickname: String?, password: String?) {
        val member = studentRepository.findById(memberId).orElseThrow { IllegalArgumentException("회원을 찾을 수 없습니다. memberId=$memberId") }

        val updatedByNickname = updateNicknameIfPresent(memberId, member, nickname)
        val updated = updatePasswordIfPresent(updatedByNickname, password)

        if (updated == member) {
            return
        }
        studentRepository.save(updated)
    }

    private fun updateNicknameIfPresent(memberId: String, member: com.didimlog.domain.Student, nickname: String?): com.didimlog.domain.Student {
        if (nickname == null) {
            return member
        }
        if (nickname == member.nickname.value) {
            return member
        }

        validateDuplicate(memberId, nickname)
        return member.updateNickname(nickname)
    }

    private fun updatePasswordIfPresent(member: com.didimlog.domain.Student, password: String?): com.didimlog.domain.Student {
        if (password == null) {
            return member
        }
        val encoded = passwordEncoder.encode(password)
        return member.updatePassword(encoded)
    }

    private fun validateDuplicate(memberId: String, nickname: String) {
        val nicknameVo = Nickname(nickname)
        val existing = studentRepository.findByNickname(nicknameVo).orElse(null) ?: return
        if (existing.id == memberId) {
            return
        }
        throw DuplicateNicknameException("이미 사용 중인 닉네임입니다. nickname=${nicknameVo.value}")
    }
}



