package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import org.springframework.stereotype.Service

@Service
class ProblemService(
    private val solvedAcClient: SolvedAcClient,
    private val problemRepository: ProblemRepository,
    private val studentRepository: StudentRepository,
    private val bojCrawler: BojCrawler
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

    /**
     * 문제 상세 정보를 조회한다.
     * Read-Through 전략: DB에 상세 정보가 없으면 크롤링하여 가져온 후 저장한다.
     *
     * @param problemId 문제 ID
     * @return 문제 상세 정보
     * @throws BusinessException 문제를 찾을 수 없는 경우
     */
    fun getProblemDetail(problemId: Long): Problem {
        val problemIdVo = ProblemId(problemId.toString())
        val problem = problemRepository.findById(problemIdVo.value)
            .orElseThrow {
                BusinessException(ErrorCode.PROBLEM_NOT_FOUND, "문제를 찾을 수 없습니다. problemId=$problemId")
            }

        // Read-Through 전략: 상세 정보가 없으면 크롤링
        if (problem.descriptionHtml.isNullOrBlank()) {
            val crawledDetails = bojCrawler.crawlProblemDetails(problemId.toString())
            if (crawledDetails != null) {
                val updatedProblem = problem.copy(
                    descriptionHtml = crawledDetails.descriptionHtml,
                    inputDescriptionHtml = crawledDetails.inputDescriptionHtml,
                    outputDescriptionHtml = crawledDetails.outputDescriptionHtml,
                    sampleInputs = crawledDetails.sampleInputs.takeIf { it.isNotEmpty() },
                    sampleOutputs = crawledDetails.sampleOutputs.takeIf { it.isNotEmpty() }
                )
                return problemRepository.save(updatedProblem)
            }
        }

        return problem
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
