package com.didimlog.application.statistics

import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
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
    private val studentRepository: StudentRepository
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
        val totalSolvedCount = student.solutions.getAll().size

        return StatisticsInfo(
            monthlyHeatmap = monthlyHeatmap,
            categoryDistribution = categoryDistribution,
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
     * 각 날짜별 풀이 수를 집계한다.
     */
    private fun getMonthlyHeatmap(student: Student): List<HeatmapData> {
        val solutions = student.solutions.getAll()
        val today = LocalDate.now()
        val startDate = today.minusMonths(12)

        val heatmapMap = mutableMapOf<LocalDate, Int>()

        solutions.forEach { solution ->
            val solutionDate = solution.solvedAt.toLocalDate()
            if (solutionDate.isAfter(startDate.minusDays(1)) && !solutionDate.isAfter(today)) {
                heatmapMap[solutionDate] = heatmapMap.getOrDefault(solutionDate, 0) + 1
            }
        }

        return heatmapMap.map { (date, count) ->
            HeatmapData(date = date.toString(), count = count)
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
}

/**
 * 통계 정보를 담는 데이터 클래스
 */
data class StatisticsInfo(
    val monthlyHeatmap: List<HeatmapData>,
    val categoryDistribution: Map<String, Int>,
    val totalSolvedCount: Int
)

/**
 * 잔디 데이터
 */
data class HeatmapData(
    val date: String,
    val count: Int
)

