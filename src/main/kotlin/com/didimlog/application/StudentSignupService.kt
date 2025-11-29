package com.didimlog.application

import com.didimlog.domain.Student
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
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
            throw IllegalStateException("이미 가입된 BOJ ID 입니다. bojId=${bojIdVo.value}")
        }

        val user = solvedAcClient.fetchUser(bojIdVo)
        val initialTier = SolvedAcTierMapper.fromUserTier(user.tier)

        val student = Student(
            nickname = nicknameVo,
            bojId = bojIdVo,
            currentTier = initialTier
        )

        return studentRepository.save(student)
    }
}


