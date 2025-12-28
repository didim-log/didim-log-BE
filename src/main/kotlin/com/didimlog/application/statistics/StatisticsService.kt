package com.didimlog.application.statistics

import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 통계 서비스
 * 학생의 풀이 통계 데이터를 집계하여 제공한다.
 */
@Service
class StatisticsService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository
) {

    /**
     * 학생의 통계 정보를 조회한다.
     * 월별 잔디(Heatmap), 카테고리별 분포, 누적 풀이 수를 포함한다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @return 통계 정보
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getStatistics(bojId: String): StatisticsInfo {
        val student = findStudentByBojIdOrThrow(bojId)
        val monthlyHeatmap = getMonthlyHeatmap(student)
        val categoryDistribution = getCategoryDistribution()
        val algorithmCategoryDistribution = getAlgorithmCategoryDistribution(student.id!!)
        val topUsedAlgorithms = getTopUsedAlgorithms(algorithmCategoryDistribution)
        val totalSolvedCount = student.solutions.getAll().size

        return StatisticsInfo(
            monthlyHeatmap = monthlyHeatmap,
            categoryDistribution = categoryDistribution,
            algorithmCategoryDistribution = algorithmCategoryDistribution,
            topUsedAlgorithms = topUsedAlgorithms,
            totalSolvedCount = totalSolvedCount
        )
    }

    private fun findStudentByBojIdOrThrow(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        return studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
    }

    /**
     * 최근 12개월간의 월별 잔디 데이터를 생성한다.
     * 각 날짜별 풀이 수와 풀이한 문제 ID 목록을 집계한다.
     */
    private fun getMonthlyHeatmap(student: Student): List<HeatmapData> {
        val solutions = student.solutions.getAll()
        val today = LocalDate.now()
        val startDate = today.minusMonths(12)

        val heatmapMap = mutableMapOf<LocalDate, MutableList<String>>()

        solutions.forEach { solution ->
            val solutionDate = solution.solvedAt.toLocalDate()
            if (solutionDate.isAfter(startDate.minusDays(1)) && !solutionDate.isAfter(today)) {
                val problemIds = heatmapMap.getOrPut(solutionDate) { mutableListOf() }
                problemIds.add(solution.problemId.value)
            }
        }

        return heatmapMap.map { (date, problemIds) ->
            HeatmapData(
                date = date.toString(),
                count = problemIds.size,
                problemIds = problemIds.distinct()
            )
        }.sortedBy { it.date }
    }

    /**
     * 카테고리별 풀이 통계를 집계한다.
     * 문제의 카테고리를 기준으로 분류한다.
     * 
     * Note: 현재 Solution에는 카테고리 정보가 없으므로, 
     * 문제 ID를 통해 Problem을 조회해야 하지만, 
     * 성능상의 이유로 일단 빈 맵을 반환한다.
     * 향후 Solution에 카테고리 정보를 추가하거나, 
     * ProblemRepository를 주입받아 조회하도록 개선할 수 있다.
     */
    private fun getCategoryDistribution(): Map<String, Int> {
        // TODO: 향후 Solution에 카테고리 정보 추가 또는 Problem 조회 로직 구현
        return emptyMap()
    }

    /**
     * 알고리즘 카테고리별 사용 통계를 집계한다.
     * Retrospective의 solvedCategory 필드를 기준으로 집계한다.
     *
     * @param studentId 학생 ID
     * @return 알고리즘 카테고리별 사용 횟수 맵
     */
    private fun getAlgorithmCategoryDistribution(studentId: String): Map<String, Int> {
        val retrospectives = retrospectiveRepository.findAllByStudentId(studentId)
        val distribution = mutableMapOf<String, Int>()

        retrospectives.forEach { retrospective ->
            val category = retrospective.solvedCategory
            if (category != null && category.isNotBlank()) {
                distribution[category] = distribution.getOrDefault(category, 0) + 1
            }
        }

        return distribution
    }

    /**
     * 가장 많이 사용한 알고리즘 상위 3개를 반환한다.
     *
     * @param algorithmCategoryDistribution 알고리즘 카테고리별 사용 횟수 맵
     * @return 상위 알고리즘 목록 (이름, 사용 횟수)
     */
    private fun getTopUsedAlgorithms(algorithmCategoryDistribution: Map<String, Int>): List<TopUsedAlgorithm> {
        return algorithmCategoryDistribution
            .toList()
            .sortedByDescending { it.second }
            .take(3)
            .map { (name, count) -> TopUsedAlgorithm(name, count) }
    }
}

/**
 * 통계 정보를 담는 데이터 클래스
 */
data class StatisticsInfo(
    val monthlyHeatmap: List<HeatmapData>,
    val categoryDistribution: Map<String, Int>,
    val algorithmCategoryDistribution: Map<String, Int>,
    val topUsedAlgorithms: List<TopUsedAlgorithm>,
    val totalSolvedCount: Int
)

/**
 * 잔디 데이터
 */
data class HeatmapData(
    val date: String,
    val count: Int,
    val problemIds: List<String>
)

/**
 * 가장 많이 사용한 알고리즘 정보
 */
data class TopUsedAlgorithm(
    val name: String,
    val count: Int
)
