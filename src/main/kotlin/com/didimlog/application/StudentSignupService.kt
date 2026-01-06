package com.didimlog.application

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
import com.didimlog.infra.solvedac.SolvedAcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StudentSignupService(
    private val solvedAcClient: SolvedAcClient,
    private val studentRepository: StudentRepository
) {

    @Transactional
    fun registerWithSolvedAc(nickname: String, bojId: String): Student {
        val nicknameVo = Nickname(nickname)
        val bojIdVo = BojId(bojId)

        if (studentRepository.existsByBojId(bojIdVo)) {
            throw BusinessException(
                ErrorCode.DUPLICATE_BOJ_ID,
                "이미 가입된 백준 아이디입니다. bojId=${bojIdVo.value}"
            )
        }

        val user = solvedAcClient.fetchUser(bojIdVo)
        val initialTier = Tier.fromRating(user.rating)

        val student = Student(
            nickname = nicknameVo,
            provider = Provider.BOJ,
            providerId = bojIdVo.value,
            bojId = bojIdVo,
            password = null, // BOJ 직접 로그인이 아닌 경우 password는 null
            rating = user.rating,
            solvedAcTierLevel = SolvedAcTierLevel(user.tier),
            currentTier = initialTier,
            role = Role.USER
        )

        return studentRepository.save(student)
    }
}
