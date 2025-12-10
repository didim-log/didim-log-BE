package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.solvedac.ProblemCategoryMapper
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

        // 태그 추출: 한글 태그를 영문 표준명으로 변환
        val tags = ProblemCategoryMapper.extractTagsToEnglish(response.tags)
        val category = ProblemCategoryMapper.determineCategory(tags)

        val problem = Problem(
            id = ProblemId(response.problemId.toString()),
            title = response.titleKo,
            category = category,
            difficulty = difficultyTier,
            level = response.level,
            url = solvedAcProblemUrl(response.problemId),
            tags = tags
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
        val newRating = userResponse.rating

        val student = studentOptional.get()
        if (student.rating == newRating) {
            return
        }

        val updatedStudent = student.updateInfo(newRating)
        studentRepository.save(updatedStudent)
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
