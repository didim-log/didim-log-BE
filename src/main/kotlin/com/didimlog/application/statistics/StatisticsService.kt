package com.didimlog.application.statistics

import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.RetrospectiveStatisticsView
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

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
     * 모든 집계 로직은 백엔드에서 처리하여 프론트엔드에 전달한다.
     *
     * @param studentId 학생 ID (JWT 토큰에서 추출)
     * @return 통계 정보
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getStatistics(studentId: String): StatisticsInfo {
        val student = findStudentByIdOrThrow(studentId)
        val retrospectives = retrospectiveRepository.findStatisticsByStudentId(studentId)
        val monthlyHeatmap = getMonthlyHeatmap(retrospectives)
        val totalSolvedCount = getTotalSolvedCount(student)
        val totalRetrospectives = getTotalRetrospectives(retrospectives)
        val totalFailures = getTotalFailures(retrospectives)
        val averageSolveTime = getAverageSolveTime(student)
        val successRate = getSuccessRate(student)
        
        // 성공한 문제의 카테고리별 통계 (Radar/Bar Chart용)
        val categoryStats = getCategoryStats(retrospectives)
        
        // 실패한 문제의 카테고리별 통계 (Weakness Analysis용)
        val weaknessStats = getWeaknessStats(retrospectives)

        return StatisticsInfo(
            monthlyHeatmap = monthlyHeatmap,
            totalSolvedCount = totalSolvedCount,
            totalRetrospectives = totalRetrospectives,
            totalFailures = totalFailures,
            averageSolveTime = averageSolveTime,
            successRate = successRate,
            categoryStats = categoryStats,
            weaknessStats = weaknessStats
        )
    }

    private fun findStudentByIdOrThrow(studentId: String): Student {
        return studentRepository.findById(studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. studentId=$studentId")
            }
    }

    /**
     * 최근 365일간의 활동 히트맵 데이터를 생성한다.
     * 각 날짜별 풀이 수와 풀이한 문제 ID 목록을 집계한다.
     * 프론트엔드의 GitHub 스타일 히트맵과 일치하도록 정확히 365일 전부터 오늘까지의 데이터를 반환한다.
     * 연도별 히트맵도 함께 생성한다.
     */
    private fun getMonthlyHeatmap(retrospectives: List<RetrospectiveStatisticsView>): List<HeatmapData> {
        val today = LocalDate.now()
        val startDate = today.minusDays(364) // 정확히 365일 전 (오늘 포함하여 365일)

        val heatmapMap = mutableMapOf<LocalDate, MutableList<String>>()

        retrospectives.forEach { retrospective ->
            val solutionDate = retrospective.createdAt.toLocalDate()
            // startDate부터 today까지 포함 (둘 다 포함)
            if (!solutionDate.isBefore(startDate) && !solutionDate.isAfter(today)) {
                val problemIds = heatmapMap.getOrPut(solutionDate) { mutableListOf() }
                if (retrospective.problemId !in problemIds) {
                    problemIds.add(retrospective.problemId)
                }
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
     * 성공한 문제의 카테고리별 통계를 집계한다.
     * 회고의 solvedCategory를 기준으로 집계하며, 쉼표로 구분된 태그는 개별 카테고리로 분리하여 집계한다.
     * 예: "BFS, DP"가 있으면 BFS 1회, DP 1회로 카운트
     *
     * @param retrospectives 통계 집계용 회고 목록
     * @return 카테고리별 통계 리스트 (count 기준 내림차순 정렬)
     */
    private fun getCategoryStats(retrospectives: List<RetrospectiveStatisticsView>): List<CategoryStat> {
        val successRetrospectives = retrospectives.filter { retrospective ->
            retrospective.solutionResult == ProblemResult.SUCCESS
        }

        if (successRetrospectives.isEmpty()) {
            return emptyList()
        }

        val categoryCountMap = mutableMapOf<String, Int>()

        successRetrospectives.forEach { retrospective ->
            val category = retrospective.solvedCategory
            if (category != null && category.isNotBlank()) {
                // 쉼표로 구분된 태그를 분리하여 각각 개별 카테고리로 카운트
                val categories = category.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                categories.forEach { cat ->
                    categoryCountMap[cat] = categoryCountMap.getOrDefault(cat, 0) + 1
                }
            }
        }

        if (categoryCountMap.isEmpty()) {
            return emptyList()
        }

        // count 기준 내림차순 정렬
        return categoryCountMap
            .toList()
            .sortedByDescending { it.second }
            .map { (category, count) -> CategoryStat(category, count) }
    }


    /**
     * 조회한 통계 집계용 회고 수를 반환한다.
     *
     * @param retrospectives 통계 집계용 회고 목록
     * @return 총 회고 수
     */
    private fun getTotalRetrospectives(retrospectives: List<RetrospectiveStatisticsView>): Long {
        return retrospectives.size.toLong()
    }

    /**
     * 학생의 총 실패 회고 수를 반환한다.
     * FAIL 또는 TIME_OVER 상태인 Retrospective 문서의 개수를 반환한다.
     * 태그의 합계가 아닌 실제 문서 개수를 반환한다.
     *
     * @param retrospectives 통계 집계용 회고 목록
     * @return 총 실패 회고 수
     */
    private fun getTotalFailures(retrospectives: List<RetrospectiveStatisticsView>): Long {
        return retrospectives.count { retrospective ->
            retrospective.solutionResult == ProblemResult.FAIL || retrospective.solutionResult == ProblemResult.TIME_OVER
        }.toLong()
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
     * 실패한 문제의 카테고리별 통계를 집계한다.
     * FAIL 또는 TIME_OVER인 회고의 solvedCategory를 기준으로 집계하며, 쉼표로 구분된 태그는 개별 카테고리로 분리하여 집계한다.
     * 예: "BFS, DP"가 있으면 BFS 1회, DP 1회로 카운트
     *
     * @param retrospectives 통계 집계용 회고 목록
     * @return 카테고리별 통계 리스트 (count 기준 내림차순 정렬)
     */
    private fun getWeaknessStats(retrospectives: List<RetrospectiveStatisticsView>): List<CategoryStat> {
        val failedRetrospectives = retrospectives.filter { retrospective ->
            retrospective.solutionResult == ProblemResult.FAIL || retrospective.solutionResult == ProblemResult.TIME_OVER
        }

        if (failedRetrospectives.isEmpty()) {
            return emptyList()
        }

        val categoryCountMap = mutableMapOf<String, Int>()

        failedRetrospectives.forEach { retrospective ->
            val category = retrospective.solvedCategory
            if (category != null && category.isNotBlank()) {
                // 쉼표로 구분된 태그를 분리하여 각각 개별 카테고리로 카운트
                val categories = category.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                categories.forEach { cat ->
                    categoryCountMap[cat] = categoryCountMap.getOrDefault(cat, 0) + 1
                }
            }
        }

        if (categoryCountMap.isEmpty()) {
            return emptyList()
        }

        // count 기준 내림차순 정렬
        return categoryCountMap
            .toList()
            .sortedByDescending { it.second }
            .map { (category, count) -> CategoryStat(category, count) }
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

    /**
     * 특정 연도의 활동 히트맵 데이터를 조회한다.
     * 해당 연도의 1월 1일 00:00:00부터 12월 31일 23:59:59까지의 회고 데이터를 집계한다.
     * 현재 연도인 경우 오늘까지만 조회된다.
     *
     * @param studentId 학생 ID (JWT 토큰에서 추출)
     * @param year 조회할 연도 (0이면 현재 연도)
     * @return 히트맵 데이터 리스트
     */
    @Transactional(readOnly = true)
    fun getHeatmapByYear(studentId: String, year: Int): List<HeatmapData> {
        findStudentByIdOrThrow(studentId)

        val today = LocalDate.now()
        val targetYear = if (year == 0) today.year else year
        val yearStart = LocalDate.of(targetYear, 1, 1)
        if (yearStart.isAfter(today)) {
            return emptyList()
        }
        val endExclusive = if (targetYear == today.year) {
            today.plusDays(1)
        } else {
            yearStart.plusYears(1)
        }

        val yearRetrospectives = retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
            studentId = studentId,
            startInclusive = yearStart.atStartOfDay(),
            endExclusive = endExclusive.atStartOfDay()
        )

        // 날짜별로 그룹화하여 집계
        val heatmapMap = mutableMapOf<LocalDate, MutableList<String>>()
        yearRetrospectives.forEach { retrospective ->
            val retrospectiveDate = retrospective.createdAt.toLocalDate()
            val problemIds = heatmapMap.getOrPut(retrospectiveDate) { mutableListOf() }
            if (retrospective.problemId !in problemIds) {
                problemIds.add(retrospective.problemId)
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
}

/**
 * 통계 정보를 담는 데이터 클래스
 */
data class StatisticsInfo(
    val monthlyHeatmap: List<HeatmapData>,
    val totalSolvedCount: Int,
    val totalRetrospectives: Long,
    val totalFailures: Long,
    val averageSolveTime: Double,
    val successRate: Double,
    val categoryStats: List<CategoryStat>, // 성공한 문제의 카테고리별 통계
    val weaknessStats: List<CategoryStat>  // 실패한 문제의 카테고리별 통계
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
 * 카테고리별 통계 정보
 */
data class CategoryStat(
    val category: String,
    val count: Int
)
