package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import org.springframework.stereotype.Service

@Service
class ProblemService(
    private val solvedAcClient: SolvedAcClient,
    private val problemRepository: ProblemRepository,
    private val studentRepository: StudentRepository
) {

    fun syncProblem(problemId: Int) {
        val response = solvedAcClient.fetchProblem(problemId)
        val difficultyTier = SolvedAcTierMapper.fromProblemLevel(response.level)

        val problem = Problem(
            id = ProblemId(response.problemId.toString()),
            title = response.titleKo,
            category = "UNKNOWN",
            difficulty = difficultyTier,
            level = response.level,
            url = solvedAcProblemUrl(response.problemId)
        )

        problemRepository.save(problem)
    }

    fun syncUserTier(bojId: String) {
        val targetBojId = BojId(bojId)
        val studentOptional = studentRepository.findByBojId(targetBojId)
        if (studentOptional.isEmpty) {
            return
        }

        val userResponse = solvedAcClient.fetchUser(targetBojId)
        val targetTier = SolvedAcTierMapper.fromUserTier(userResponse.tier)

        val student = studentOptional.get()
        if (student.tier() == targetTier) {
            return
        }

        val updatedStudent = student.updateTier(targetTier)
        studentRepository.save(updatedStudent)
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}


