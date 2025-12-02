package com.didimlog.application.dashboard

import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.didimlog.global.exception.BusinessException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("DashboardService 테스트")
class DashboardServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val recommendationService: RecommendationService = mockk()

    private val dashboardService = DashboardService(
        studentRepository,
        recommendationService
    )

    @Test
    @DisplayName("getDashboard는 학생의 대시보드 정보를 조회한다")
    fun `대시보드 정보 조회`() {
        // given
        val bojId = "tester123"
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId(bojId),
            password = "test-password",
            currentTier = Tier.GOLD
        )

        val problem1 = Problem(
            id = ProblemId("1000"),
            title = "Problem 1",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )
        val problem2 = Problem(
            id = ProblemId("1001"),
            title = "Problem 2",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.SILVER,
            level = 7,
            url = "https://www.acmicpc.net/problem/1001"
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { recommendationService.recommendProblems(bojId, count = 3) } returns listOf(problem1, problem2)

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.currentTier).isEqualTo(Tier.GOLD)
        assertThat(result.recommendedProblems).hasSize(2)
        assertThat(result.recommendedProblems).containsExactlyInAnyOrder(problem1, problem2)
        verify(exactly = 1) { recommendationService.recommendProblems(bojId, count = 3) }
    }

    @Test
    @DisplayName("getDashboard는 최근 풀이 기록을 최신순으로 반환한다")
    fun `최근 풀이 기록 최신순 정렬`() {
        // given
        val bojId = "tester123"
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId(bojId),
            password = "test-password",
            currentTier = Tier.BRONZE
        )

        val problem1 = Problem(
            id = ProblemId("p1"),
            title = "Problem 1",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/p1"
        )

        val solution1 = Solution(
            problemId = ProblemId("p1"),
            timeTaken = TimeTakenSeconds(100L),
            result = ProblemResult.SUCCESS,
            solvedAt = LocalDateTime.now().minusDays(2)
        )
        val solution2 = Solution(
            problemId = ProblemId("p2"),
            timeTaken = TimeTakenSeconds(120L),
            result = ProblemResult.SUCCESS,
            solvedAt = LocalDateTime.now().minusDays(1)
        )
        val solution3 = Solution(
            problemId = ProblemId("p3"),
            timeTaken = TimeTakenSeconds(150L),
            result = ProblemResult.FAIL,
            solvedAt = LocalDateTime.now()
        )

        val studentAfterFirst = student.solveProblem(problem1, TimeTakenSeconds(100L), isSuccess = true)
        val studentAfterSecond = studentAfterFirst.solveProblem(problem1, TimeTakenSeconds(120L), isSuccess = true)
        val studentWithSolutions = studentAfterSecond.solveProblem(problem1, TimeTakenSeconds(150L), isSuccess = false)

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(studentWithSolutions)
        every { recommendationService.recommendProblems(bojId, count = 3) } returns emptyList()

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.recentSolutions).hasSize(3)
        val solvedAts = result.recentSolutions.map { it.solvedAt }
        assertThat(solvedAts).isSortedAccordingTo { a, b -> b.compareTo(a) } // 최신순 정렬 확인
    }

    @Test
    @DisplayName("getDashboard는 최근 풀이 기록을 최대 10개까지만 반환한다")
    fun `최근 풀이 기록 최대 10개 제한`() {
        // given
        val bojId = "tester123"
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId(bojId),
            password = "test-password",
            currentTier = Tier.BRONZE
        )

        val problem = Problem(
            id = ProblemId("p1"),
            title = "Problem 1",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/p1"
        )

        var currentStudent = student
        repeat(15) { index ->
            currentStudent = currentStudent.solveProblem(
                problem,
                TimeTakenSeconds((100 + index).toLong()),
                isSuccess = true
            )
        }
        val studentWithManySolutions = currentStudent

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(studentWithManySolutions)
        every { recommendationService.recommendProblems(bojId, count = 3) } returns emptyList()

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.recentSolutions).hasSize(10)
    }

    @Test
    @DisplayName("getDashboard는 학생이 없으면 예외를 발생시킨다")
    fun `학생이 없으면 예외`() {
        // given
        every { studentRepository.findByBojId(BojId("missing")) } returns Optional.empty()

        // expect
        assertThrows<com.didimlog.global.exception.BusinessException> {
            dashboardService.getDashboard("missing")
        }
    }
}

