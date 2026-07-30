package com.didimlog.application.problem

import com.didimlog.domain.Problem
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
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

        problemRepository.upsertMetadata(problem)
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
        val updatedStudent = student.updateInfo(newRating)
        if (
            student.rating == updatedStudent.rating &&
            student.solvedAcTierLevel == updatedStudent.solvedAcTierLevel &&
            student.currentTier == updatedStudent.currentTier
        ) {
            return
        }

        val studentId = student.id ?: return
        studentRepository.updateSolvedAcProfileById(
            studentId = studentId,
            expectedBojId = targetBojId,
            rating = updatedStudent.rating,
            solvedAcTierLevel = updatedStudent.solvedAcTierLevel,
            currentTier = updatedStudent.currentTier
        )
    }

    /**
     * 문제 상세 정보를 조회한다.
     * Read-Through 전략: DB에 문제가 없으면 Solved.ac API로 메타데이터를 조회하고,
     * 상세 정보가 없으면 크롤링하여 가져온 후 저장한다.
     *
     * @param problemId 문제 ID
     * @return 문제 상세 정보
     * @throws BusinessException Solved.ac에서 문제를 찾을 수 없는 경우
     */
    fun getProblemDetail(problemId: Long): Problem {
        val problemIdVo = ProblemId(problemId.toString())
        val existingProblem = problemRepository.findById(problemIdVo.value)

        if (existingProblem.isEmpty) {
            return createProblemFromSolvedAc(problemId.toInt(), includeDetails = true)
        }

        val problem = existingProblem.get()
        val problemWithDetails = enrichProblemWithDetails(problem, problemId.toString())
        return problemWithDetails
    }

    /**
     * 문제 메타데이터를 조회한다.
     * 상세 HTML 크롤링은 수행하지 않으므로 템플릿 렌더/미리보기 등 저지연 경로에서 사용한다.
     */
    fun getProblemMeta(problemId: Long): Problem {
        val problemIdVo = ProblemId(problemId.toString())
        val existingProblem = problemRepository.findById(problemIdVo.value)
        if (existingProblem.isPresent) {
            return existingProblem.get()
        }
        return createProblemFromSolvedAc(problemId.toInt(), includeDetails = false)
    }

    /**
     * DB에 저장된 문제 메타데이터만 조회한다.
     * 외부 API 호출/크롤링은 수행하지 않는다.
     */
    fun getProblemMetaIfExists(problemId: Long): Problem? {
        val problemIdVo = ProblemId(problemId.toString())
        return problemRepository.findById(problemIdVo.value).orElse(null)
    }

    private fun createProblemFromSolvedAc(problemId: Int, includeDetails: Boolean): Problem {
        val response = solvedAcClient.fetchProblem(problemId)
        val difficultyTier = SolvedAcTierMapper.fromProblemLevel(response.level)
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

        val savedProblem = problemRepository.save(problem)
        if (!includeDetails) {
            return savedProblem
        }
        return enrichProblemWithDetails(savedProblem, problemId.toString())
    }

    private fun enrichProblemWithDetails(problem: Problem, problemId: String): Problem {
        if (problem.descriptionHtml.isNullOrBlank()) {
            val crawledDetails = bojCrawler.crawlProblemDetails(problemId)
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
