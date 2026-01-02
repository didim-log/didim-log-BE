package com.didimlog.application.admin

import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 관리자 대시보드 차트 데이터 서비스
 * 통계 카드 클릭 시 표시할 트렌드 차트 데이터를 제공한다.
 */
@Service
class AdminDashboardChartService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository
) {

    /**
     * 차트 데이터를 조회한다.
     *
     * @param dataType 데이터 타입 (USER, SOLUTION, RETROSPECTIVE)
     * @param period 기간 (DAILY, WEEKLY, MONTHLY)
     * @return 차트 데이터 리스트 (날짜, 값)
     */
    @Transactional(readOnly = true)
    fun getChartData(dataType: ChartDataType, period: ChartPeriod): List<ChartDataPoint> {
        return when (dataType) {
            ChartDataType.USER -> getUserChartData(period)
            ChartDataType.SOLUTION -> getSolutionChartData(period)
            ChartDataType.RETROSPECTIVE -> getRetrospectiveChartData(period)
        }
    }

    /**
     * 회원 수 차트 데이터를 조회한다.
     */
    private fun getUserChartData(period: ChartPeriod): List<ChartDataPoint> {
        val allStudents = studentRepository.findAll()
        val dateFormatter = when (period) {
            ChartPeriod.DAILY -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
            ChartPeriod.WEEKLY -> DateTimeFormatter.ofPattern("yyyy-'W'ww")
            ChartPeriod.MONTHLY -> DateTimeFormatter.ofPattern("yyyy-MM")
        }

        val grouped = allStudents.groupBy { student ->
            val localDate = student.createdAt.toLocalDate()
            when (period) {
                ChartPeriod.DAILY -> localDate.format(dateFormatter)
                ChartPeriod.WEEKLY -> {
                    val year = localDate.year
                    val week = localDate.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                    "$year-W${week.toString().padStart(2, '0')}"
                }
                ChartPeriod.MONTHLY -> localDate.format(dateFormatter)
            }
        }

        // 누적 합계로 변환
        val sortedKeys = grouped.keys.sorted()
        var cumulative = 0L
        return sortedKeys.map { key ->
            cumulative += grouped[key]!!.size
            ChartDataPoint(date = key, value = cumulative)
        }
    }

    /**
     * 해결된 문제 수 차트 데이터를 조회한다.
     */
    private fun getSolutionChartData(period: ChartPeriod): List<ChartDataPoint> {
        val allStudents = studentRepository.findAll()
        val dateFormatter = when (period) {
            ChartPeriod.DAILY -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
            ChartPeriod.WEEKLY -> DateTimeFormatter.ofPattern("yyyy-'W'ww")
            ChartPeriod.MONTHLY -> DateTimeFormatter.ofPattern("yyyy-MM")
        }

        // 모든 학생의 성공한 Solution을 날짜별로 그룹화
        val solutionMap = mutableMapOf<String, MutableSet<String>>() // 날짜 -> problemId Set

        allStudents.forEach { student ->
            student.solutions.getAll()
                .filter { it.isSuccess() }
                .forEach { solution ->
                    val localDate = solution.solvedAt.toLocalDate()
                    val dateKey = when (period) {
                        ChartPeriod.DAILY -> localDate.format(dateFormatter)
                        ChartPeriod.WEEKLY -> {
                            val year = localDate.year
                            val week = localDate.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                            "$year-W${week.toString().padStart(2, '0')}"
                        }
                        ChartPeriod.MONTHLY -> localDate.format(dateFormatter)
                    }
                    solutionMap.getOrPut(dateKey) { mutableSetOf() }.add(solution.problemId.value)
                }
        }

        // 누적 합계로 변환
        val sortedKeys = solutionMap.keys.sorted()
        var cumulative = 0L
        return sortedKeys.map { key ->
            cumulative += solutionMap[key]!!.size
            ChartDataPoint(date = key, value = cumulative)
        }
    }

    /**
     * 회고 수 차트 데이터를 조회한다.
     */
    private fun getRetrospectiveChartData(period: ChartPeriod): List<ChartDataPoint> {
        val allRetrospectives = retrospectiveRepository.findAll()
        val dateFormatter = when (period) {
            ChartPeriod.DAILY -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
            ChartPeriod.WEEKLY -> DateTimeFormatter.ofPattern("yyyy-'W'ww")
            ChartPeriod.MONTHLY -> DateTimeFormatter.ofPattern("yyyy-MM")
        }

        val grouped = allRetrospectives.groupBy { retrospective ->
            val localDate = retrospective.createdAt.toLocalDate()
            when (period) {
                ChartPeriod.DAILY -> localDate.format(dateFormatter)
                ChartPeriod.WEEKLY -> {
                    val year = localDate.year
                    val week = localDate.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                    "$year-W${week.toString().padStart(2, '0')}"
                }
                ChartPeriod.MONTHLY -> localDate.format(dateFormatter)
            }
        }

        // 누적 합계로 변환
        val sortedKeys = grouped.keys.sorted()
        var cumulative = 0L
        return sortedKeys.map { key ->
            cumulative += grouped[key]!!.size
            ChartDataPoint(date = key, value = cumulative)
        }
    }
}

/**
 * 차트 데이터 타입
 */
enum class ChartDataType {
    USER,           // 회원 수
    SOLUTION,       // 해결된 문제 수
    RETROSPECTIVE   // 회고 수
}

/**
 * 차트 기간
 */
enum class ChartPeriod {
    DAILY,      // 일별
    WEEKLY,     // 주별
    MONTHLY     // 월별
}

/**
 * 차트 데이터 포인트
 */
data class ChartDataPoint(
    val date: String,   // 날짜 문자열 (형식은 period에 따라 다름)
    val value: Long     // 값
)

