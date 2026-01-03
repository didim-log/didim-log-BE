package com.didimlog.application.member

import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.DuplicateNicknameException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberService(
    private val studentRepository: StudentRepository
) {

    fun isNicknameAvailable(nickname: String): Boolean {
        val nicknameVo = tryCreateNicknameOrNull(nickname) ?: return false
        return !studentRepository.existsByNickname(nicknameVo)
    }

    @Transactional
    fun updateMyNickname(memberId: String, nickname: String) {
        val member = studentRepository.findById(memberId).orElseThrow { IllegalArgumentException("회원을 찾을 수 없습니다. memberId=$memberId") }
        validateDuplicate(memberId, nickname)
        val updated = member.updateNickname(nickname)
        studentRepository.save(updated)
    }

    private fun validateDuplicate(memberId: String, nickname: String) {
        val nicknameVo = Nickname(nickname)
        val existing = studentRepository.findByNickname(nicknameVo).orElse(null) ?: return
        if (existing.id == memberId) {
            return
        }
        throw DuplicateNicknameException("이미 사용 중인 닉네임입니다. nickname=${nicknameVo.value}")
    }

    @Transactional
    fun completeOnboarding(bojId: String) {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
        val updated = student.completeOnboarding()
        studentRepository.save(updated)
    }

    @Transactional
    fun resetOnboarding(bojId: String) {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
        val updated = student.resetOnboarding()
        studentRepository.save(updated)
    }

    private fun tryCreateNicknameOrNull(nickname: String): Nickname? {
        return try {
            Nickname(nickname)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}




