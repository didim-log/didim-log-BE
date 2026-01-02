package com.didimlog.application.statistics

import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.ProblemId
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
    private val retrospectiveRepository: RetrospectiveRepository,
    private val problemRepository: ProblemRepository
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
        val studentId = student.id ?: throw BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생 ID가 없습니다. bojId=$bojId")
        val monthlyHeatmap = getMonthlyHeatmap(student)
        val categoryDistribution = getCategoryDistribution(studentId)
        val algorithmCategoryDistribution = getAlgorithmCategoryDistribution(studentId)
        val topUsedAlgorithms = getTopUsedAlgorithms(algorithmCategoryDistribution)
        val totalSolvedCount = getTotalSolvedCount(student)
        val totalRetrospectives = getTotalRetrospectives(studentId)
        val averageSolveTime = getAverageSolveTime(student)
        val successRate = getSuccessRate(student)
        val tagRadarData = getTagRadarData(studentId)
        val weaknessAnalysis = getWeaknessAnalysis(studentId)

        return StatisticsInfo(
            monthlyHeatmap = monthlyHeatmap,
            categoryDistribution = categoryDistribution,
            algorithmCategoryDistribution = algorithmCategoryDistribution,
            topUsedAlgorithms = topUsedAlgorithms,
            totalSolvedCount = totalSolvedCount,
            totalRetrospectives = totalRetrospectives,
            averageSolveTime = averageSolveTime,
            successRate = successRate,
            tagRadarData = tagRadarData,
            weaknessAnalysis = weaknessAnalysis
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
     * 최근 365일간의 활동 히트맵 데이터를 생성한다.
     * 각 날짜별 풀이 수와 풀이한 문제 ID 목록을 집계한다.
     * 프론트엔드의 GitHub 스타일 히트맵과 일치하도록 정확히 365일 전부터 오늘까지의 데이터를 반환한다.
     * 연도별 히트맵도 함께 생성한다.
     */
    private fun getMonthlyHeatmap(student: Student): List<HeatmapData> {
        val solutions = student.solutions.getAll()
        val today = LocalDate.now()
        val startDate = today.minusDays(364) // 정확히 365일 전 (오늘 포함하여 365일)

        val heatmapMap = mutableMapOf<LocalDate, MutableList<String>>()

        solutions.forEach { solution ->
            val solutionDate = solution.solvedAt.toLocalDate()
            // startDate부터 today까지 포함 (둘 다 포함)
            if (!solutionDate.isBefore(startDate) && !solutionDate.isAfter(today)) {
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
     * 회고의 mainCategory를 기준으로 분류한다.
     * 사용자가 실제로 풀이한 문제의 카테고리 정보를 반영한다.
     *
     * @param studentId 학생 ID
     * @return 카테고리별 풀이 횟수 맵
     */
    private fun getCategoryDistribution(studentId: String): Map<String, Int> {
        val retrospectives = retrospectiveRepository.findAllByStudentId(studentId)
        val distribution = mutableMapOf<String, Int>()

        retrospectives.forEach { retrospective ->
            retrospective.mainCategory?.let { category ->
                val categoryName = category.name
                distribution[categoryName] = distribution.getOrDefault(categoryName, 0) + 1
            }
        }

        return distribution
    }

    /**
     * 알고리즘 카테고리별 사용 통계를 집계한다.
     * Retrospective의 solvedCategory 필드를 기준으로 집계한다.
     * 쉼표로 구분된 태그는 분리하여 각각 개별 알고리즘으로 카운트한다.
     * 예: "BFS, DP"가 있으면 BFS 1회, DP 1회로 카운트
     * 사용자가 회고 작성 시 선택한 카테고리만 집계한다.
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
                // 쉼표로 구분된 태그를 분리하여 각각 개별 알고리즘으로 카운트
                val algorithms = category.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                algorithms.forEach { algorithm ->
                    distribution[algorithm] = distribution.getOrDefault(algorithm, 0) + 1
                }
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

    /**
     * 학생의 총 회고 수를 반환한다.
     * DB 쿼리에서 직접 COUNT를 수행하여 성능을 최적화한다.
     *
     * @param studentId 학생 ID
     * @return 총 회고 수
     */
    private fun getTotalRetrospectives(studentId: String): Long {
        return retrospectiveRepository.countByStudentId(studentId)
    }

    /**
     * 학생의 평균 풀이 시간을 계산한다.
     * 모든 Solution의 timeTaken 평균값을 초 단위로 반환한다.
     *
     * @param student 학생 엔티티
     * @return 평균 풀이 시간 (초 단위), 데이터가 없으면 0.0
     */
    private fun getAverageSolveTime(student: Student): Double {
        val solutions = student.solutions.getAll()
        if (solutions.isEmpty()) {
            return 0.0
        }

        val totalTime = solutions.sumOf { it.timeTaken.value }
        return totalTime.toDouble() / solutions.size
    }

    /**
     * 학생의 성공률을 계산한다.
     * (성공한 풀이 수 / 전체 제출 수) * 100
     *
     * @param student 학생 엔티티
     * @return 성공률 (0.0 ~ 100.0), 소수점 첫째 자리까지 반올림
     */
    private fun getSuccessRate(student: Student): Double {
        val solutions = student.solutions.getAll()
        if (solutions.isEmpty()) {
            return 0.0
        }

        val successCount = solutions.count { it.result == ProblemResult.SUCCESS }
        val rate = (successCount.toDouble() / solutions.size) * 100.0
        return Math.round(rate * 10.0) / 10.0
    }

    /**
     * 학생이 회고에서 선택한 알고리즘 카테고리를 집계하여 레이더 차트용 데이터를 생성한다.
     * 회고의 solvedCategory를 기준으로 집계하여 사용자가 실제로 사용한 알고리즘을 반영한다.
     * 쉼표로 구분된 태그는 하나의 카테고리로 취급한다.
     *
     * @param studentId 학생 ID
     * @return 태그별 통계 리스트 (상위 5개)
     */
    private fun getTagRadarData(studentId: String): List<TagStat> {
        val retrospectives = retrospectiveRepository.findAllByStudentId(studentId)
        if (retrospectives.isEmpty()) {
            return emptyList()
        }

        // 회고의 solvedCategory를 기준으로 카운팅
        val tagCountMap = mutableMapOf<String, Int>()
        retrospectives.forEach { retrospective ->
            val category = retrospective.solvedCategory
            if (category != null && category.isNotBlank()) {
                // 쉼표로 구분된 태그는 하나의 카테고리로 취급
                tagCountMap[category] = tagCountMap.getOrDefault(category, 0) + 1
            }
        }

        if (tagCountMap.isEmpty()) {
            return emptyList()
        }

        // 상위 5개 태그 추출
        val topTags = tagCountMap
            .toList()
            .sortedByDescending { it.second }
            .take(5)

        // 최대 카운트 값 (fullMark)
        val maxCount = topTags.maxOf { it.second }

        return topTags.map { (tag, count) ->
            TagStat(
                tag = tag,
                count = count,
                fullMark = maxCount
            )
        }
    }

    /**
     * 취약점 분석 데이터를 생성한다.
     * FAIL 또는 TIME_OVER인 회고를 분석하여 가장 빈번한 실패 카테고리와 실패 원인을 파악한다.
     *
     * @param studentId 학생 ID
     * @return 취약점 분석 데이터
     */
    private fun getWeaknessAnalysis(studentId: String): WeaknessAnalysis? {
        val retrospectives = retrospectiveRepository.findAllByStudentId(studentId)
        val failedRetrospectives = retrospectives.filter { retrospective ->
            retrospective.solutionResult == ProblemResult.FAIL || retrospective.solutionResult == ProblemResult.TIME_OVER
        }

        if (failedRetrospectives.isEmpty()) {
            return null
        }

        // 카테고리별 실패 횟수 집계
        val categoryFailures = mutableMapOf<String, Int>()
        val resultTypeCounts = mutableMapOf<ProblemResult, Int>()

        failedRetrospectives.forEach { retrospective ->
            // 카테고리 집계
            retrospective.mainCategory?.let { category ->
                val categoryName = category.name
                categoryFailures[categoryName] = categoryFailures.getOrDefault(categoryName, 0) + 1
            }

            // 결과 타입 집계
            retrospective.solutionResult?.let { result ->
                resultTypeCounts[result] = resultTypeCounts.getOrDefault(result, 0) + 1
            }
        }

        // 가장 빈번한 실패 카테고리
        val topFailureCategory = categoryFailures.maxByOrNull { it.value }

        // 가장 빈번한 실패 원인
        val topFailureReason = resultTypeCounts.maxByOrNull { it.value }?.key

        // 카테고리별 실패 분포 (상위 8개)
        val categoryFailuresList = categoryFailures
            .toList()
            .sortedByDescending { it.second }
            .take(8)
            .map { (category, count) -> CategoryFailure(category, count) }

        return WeaknessAnalysis(
            totalFailures = failedRetrospectives.size,
            topCategory = topFailureCategory?.key,
            topCategoryCount = topFailureCategory?.value ?: 0,
            topReason = topFailureReason,
            categoryFailures = categoryFailuresList
        )
    }

    /**
     * 학생이 풀이한 고유한 문제의 개수를 반환한다.
     * 성공한 풀이(isSuccess = true) 중에서 DISTINCT problemId의 개수를 계산한다.
     *
     * @param student 학생 엔티티
     * @return 고유한 문제 풀이 수
     */
    private fun getTotalSolvedCount(student: Student): Int {
        val solutions = student.solutions.getAll()
        // 성공한 풀이 중에서 고유한 problemId의 개수를 계산
        val uniqueSolvedProblems = solutions
            .filter { it.result == ProblemResult.SUCCESS }
            .map { it.problemId.value }
            .distinct()
            .size
        
        return uniqueSolvedProblems
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
    val totalSolvedCount: Int,
    val totalRetrospectives: Long,
    val averageSolveTime: Double,
    val successRate: Double,
    val tagRadarData: List<TagStat>,
    val weaknessAnalysis: WeaknessAnalysis?
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

/**
 * 태그별 통계 정보 (레이더 차트용)
 */
data class TagStat(
    val tag: String,
    val count: Int,
    val fullMark: Int
)

/**
 * 취약점 분석 정보
 */
data class WeaknessAnalysis(
    val totalFailures: Int,
    val topCategory: String?,
    val topCategoryCount: Int,
    val topReason: ProblemResult?,
    val categoryFailures: List<CategoryFailure>
)

/**
 * 카테고리별 실패 정보
 */
data class CategoryFailure(
    val category: String,
    val count: Int
)
