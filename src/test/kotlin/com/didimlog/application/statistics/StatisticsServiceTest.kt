package com.didimlog.application.statistics

import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.RetrospectiveStatisticsView
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalTime
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("StatisticsService 특성화 테스트")
class StatisticsServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val statisticsService = StatisticsService(studentRepository, retrospectiveRepository)

    @Test
    @DisplayName("전체 통계와 최근 365일 히트맵을 기존 집계 규칙대로 계산한다")
    fun `전체 통계와 최근 365일 히트맵 집계`() {
        val today = LocalDate.now()
        val solutions = Solutions().apply {
            add(solution("1000", 10L, ProblemResult.SUCCESS))
            add(solution("1000", 20L, ProblemResult.SUCCESS))
            add(solution("2000", 30L, ProblemResult.FAIL))
        }
        val retrospectives = listOf(
            retrospective(
                problemId = "duplicate-problem",
                date = today.minusDays(1),
                result = ProblemResult.SUCCESS,
                solvedCategory = " DP,  Graph , , "
            ),
            retrospective(
                problemId = "duplicate-problem",
                date = today.minusDays(1),
                result = ProblemResult.SUCCESS,
                solvedCategory = "DP"
            ),
            retrospective(
                problemId = "boundary-problem",
                date = today.minusDays(364),
                result = ProblemResult.FAIL,
                solvedCategory = " Greedy, "
            ),
            retrospective(
                problemId = "too-old-problem",
                date = today.minusDays(365),
                result = ProblemResult.TIME_OVER,
                solvedCategory = " ,  "
            ),
            retrospective(
                problemId = "null-result-problem",
                date = today,
                result = null,
                solvedCategory = "Ignored"
            ),
            retrospective(
                problemId = "future-problem",
                date = today.plusDays(1),
                result = ProblemResult.TIME_OVER,
                solvedCategory = "Binary Search"
            )
        )
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student(solutions))
        every { retrospectiveRepository.findStatisticsByStudentId(STUDENT_ID) } returns retrospectives

        val result = statisticsService.getStatistics(STUDENT_ID)

        assertThat(result.totalRetrospectives).isEqualTo(6L)
        assertThat(result.totalFailures).isEqualTo(3L)
        assertThat(result.totalSolvedCount).isEqualTo(1)
        assertThat(result.averageSolveTime).isEqualTo(20.0)
        assertThat(result.successRate).isEqualTo(66.7)
        assertThat(result.categoryStats).containsExactly(
            CategoryStat("DP", 2),
            CategoryStat("Graph", 1)
        )
        assertThat(result.weaknessStats).containsExactlyInAnyOrder(
            CategoryStat("Greedy", 1),
            CategoryStat("Binary Search", 1)
        )
        assertThat(result.monthlyHeatmap.map(HeatmapData::date)).containsExactly(
            today.minusDays(364).toString(),
            today.minusDays(1).toString(),
            today.toString()
        )
        assertThat(result.monthlyHeatmap.flatMap(HeatmapData::problemIds))
            .doesNotContain("too-old-problem", "future-problem")

        val duplicateDay = result.monthlyHeatmap.single { it.date == today.minusDays(1).toString() }
        assertThat(duplicateDay.count).isEqualTo(1)
        assertThat(duplicateDay.problemIds).containsExactly("duplicate-problem")
        verify(exactly = 1) { retrospectiveRepository.findStatisticsByStudentId(STUDENT_ID) }
    }

    @Test
    @DisplayName("풀이와 회고가 없으면 모든 통계는 0 또는 빈 목록이다")
    fun `빈 데이터 통계`() {
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student())
        every { retrospectiveRepository.findStatisticsByStudentId(STUDENT_ID) } returns emptyList()

        val result = statisticsService.getStatistics(STUDENT_ID)

        assertThat(result.totalRetrospectives).isZero()
        assertThat(result.totalFailures).isZero()
        assertThat(result.totalSolvedCount).isZero()
        assertThat(result.averageSolveTime).isZero()
        assertThat(result.successRate).isZero()
        assertThat(result.monthlyHeatmap).isEmpty()
        assertThat(result.categoryStats).isEmpty()
        assertThat(result.weaknessStats).isEmpty()
        verify(exactly = 1) { retrospectiveRepository.findStatisticsByStudentId(STUDENT_ID) }
    }

    @Test
    @DisplayName("과거 연도 히트맵은 1월 1일부터 12월 31일까지 포함한다")
    fun `과거 연도 히트맵 범위`() {
        val targetYear = LocalDate.now().year - 1
        val yearStart = LocalDate.of(targetYear, 1, 1)
        val middleOfYear = LocalDate.of(targetYear, 6, 15)
        val yearEnd = LocalDate.of(targetYear, 12, 31)
        val retrospectives = listOf(
            retrospective("before-year", yearStart.minusDays(1)),
            retrospective("year-start", yearStart),
            retrospective("middle-year", middleOfYear),
            retrospective("year-end", yearEnd),
            retrospective("after-year", yearEnd.plusDays(1))
        )
        val yearRetrospectives = retrospectives.filter { retrospective ->
            val date = retrospective.createdAt.toLocalDate()
            !date.isBefore(yearStart) && !date.isAfter(yearEnd)
        }
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student())
        every {
            retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
                STUDENT_ID,
                yearStart.atStartOfDay(),
                yearStart.plusYears(1).atStartOfDay()
            )
        } returns yearRetrospectives

        val result = statisticsService.getHeatmapByYear(STUDENT_ID, targetYear)

        assertThat(result.map(HeatmapData::date)).containsExactly(
            yearStart.toString(),
            middleOfYear.toString(),
            yearEnd.toString()
        )
        assertThat(result.flatMap(HeatmapData::problemIds))
            .containsExactly("year-start", "middle-year", "year-end")
        verify(exactly = 1) {
            retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
                STUDENT_ID,
                yearStart.atStartOfDay(),
                yearStart.plusYears(1).atStartOfDay()
            )
        }
    }

    @Test
    @DisplayName("현재 연도는 오늘까지만 포함하고 미래 연도는 빈 목록이다")
    fun `현재 및 미래 연도 히트맵 범위`() {
        val today = LocalDate.now()
        val futureYear = today.year + 1
        val retrospectives = listOf(retrospective("today-problem", today))
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student())
        every {
            retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
                STUDENT_ID,
                LocalDate.of(today.year, 1, 1).atStartOfDay(),
                today.plusDays(1).atStartOfDay()
            )
        } returns retrospectives

        val currentYearResult = statisticsService.getHeatmapByYear(STUDENT_ID, 0)
        val futureYearResult = statisticsService.getHeatmapByYear(STUDENT_ID, futureYear)

        assertThat(currentYearResult).containsExactly(
            HeatmapData(
                date = today.toString(),
                count = 1,
                problemIds = listOf("today-problem")
            )
        )
        assertThat(futureYearResult).isEmpty()
        verify(exactly = 1) {
            retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
                STUDENT_ID,
                LocalDate.of(today.year, 1, 1).atStartOfDay(),
                today.plusDays(1).atStartOfDay()
            )
        }
    }

    @Test
    @DisplayName("미래 연도 요청도 학생 존재를 먼저 확인한다")
    fun `연도별 히트맵은 학생 존재를 먼저 확인`() {
        val futureYear = LocalDate.now().year + 1
        every { studentRepository.findById(STUDENT_ID) } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            statisticsService.getHeatmapByYear(STUDENT_ID, futureYear)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 1) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 0) {
            retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
                any(),
                any(),
                any()
            )
        }
    }

    private fun student(solutions: Solutions = Solutions()): Student {
        return Student(
            id = STUDENT_ID,
            nickname = Nickname("statsuser"),
            provider = Provider.BOJ,
            providerId = "statsuser",
            bojId = BojId("statsuser"),
            currentTier = Tier.BRONZE,
            role = Role.USER,
            solutions = solutions
        )
    }

    private fun solution(
        problemId: String,
        timeTakenSeconds: Long,
        result: ProblemResult
    ): Solution {
        return Solution(
            problemId = ProblemId(problemId),
            timeTaken = TimeTakenSeconds(timeTakenSeconds),
            result = result
        )
    }

    private fun retrospective(
        problemId: String,
        date: LocalDate,
        result: ProblemResult? = ProblemResult.SUCCESS,
        solvedCategory: String? = null
    ): RetrospectiveStatisticsView {
        return RetrospectiveStatisticsView(
            problemId = problemId,
            createdAt = date.atTime(LocalTime.NOON),
            solutionResult = result,
            solvedCategory = solvedCategory
        )
    }

    companion object {
        private const val STUDENT_ID = "student-id"
    }
}
