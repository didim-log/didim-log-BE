package com.didimlog.application.dashboard

import com.didimlog.application.quote.QuoteService
import com.didimlog.domain.Quote
import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("DashboardService 테스트")
class DashboardServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val quoteService: QuoteService = mockk()

    private val dashboardService = DashboardService(
        studentRepository,
        quoteService
    )

    @Test
    @DisplayName("오늘 푼 문제가 제대로 포함되는지 검증한다")
    fun `오늘 푼 문제 포함 검증`() {
        // given
        val bojId = "testuser"
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        val todaySolution1 = Solution(
            problemId = ProblemId("1000"),
            timeTaken = TimeTakenSeconds(120),
            result = ProblemResult.SUCCESS,
            solvedAt = LocalDateTime.now()
        )
        val todaySolution2 = Solution(
            problemId = ProblemId("1001"),
            timeTaken = TimeTakenSeconds(90),
            result = ProblemResult.SUCCESS,
            solvedAt = LocalDateTime.now().minusHours(2)
        )
        val yesterdaySolution = Solution(
            problemId = ProblemId("1002"),
            timeTaken = TimeTakenSeconds(150),
            result = ProblemResult.SUCCESS,
            solvedAt = LocalDateTime.of(yesterday, LocalDateTime.now().toLocalTime())
        )

        val solutions = com.didimlog.domain.Solutions().apply {
            add(todaySolution1)
            add(todaySolution2)
            add(yesterdaySolution)
        }

        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 850,
            currentTier = Tier.fromRating(850),
            role = Role.USER,
            solutions = solutions,
            consecutiveSolveDays = 5
        )

        val quote = Quote(content = "테스트 명언", author = "테스트 작가")

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { quoteService.getRandomQuote() } returns quote

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.todaySolvedCount).isEqualTo(2)
        assertThat(result.todaySolvedProblems).hasSize(2)
        assertThat(result.todaySolvedProblems.map { it.problemId }).containsExactlyInAnyOrder("1000", "1001")
        assertThat(result.studentProfile.nickname).isEqualTo("testuser")
        assertThat(result.studentProfile.bojId).isEqualTo(bojId)
        assertThat(result.studentProfile.consecutiveSolveDays).isEqualTo(5)
        assertThat(result.currentRating).isEqualTo(850)
        assertThat(result.currentTierTitle).isEqualTo("Gold V")
        assertThat(result.nextTierTitle).isEqualTo("Gold IV")
        assertThat(result.requiredRatingForNextTier).isEqualTo(950)
        assertThat(result.progressPercentage).isEqualTo(33)
        assertThat(result.quote).isNotNull()
        assertThat(result.quote?.content).isEqualTo("테스트 명언")
    }

    @Test
    @DisplayName("오늘 푼 문제가 없으면 빈 리스트를 반환한다")
    fun `오늘 푼 문제 없음`() {
        // given
        val bojId = "testuser"
        val yesterday = LocalDate.now().minusDays(1)

        val yesterdaySolution = Solution(
            problemId = ProblemId("1000"),
            timeTaken = TimeTakenSeconds(120),
            result = ProblemResult.SUCCESS,
            solvedAt = LocalDateTime.of(yesterday, LocalDateTime.now().toLocalTime())
        )

        val solutions = com.didimlog.domain.Solutions().apply {
            add(yesterdaySolution)
        }

        val student = Student(
            id = "student-id",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            solutions = solutions
        )

        val quote = Quote(content = "테스트 명언", author = "테스트 작가")

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { quoteService.getRandomQuote() } returns quote

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.todaySolvedCount).isEqualTo(0)
        assertThat(result.todaySolvedProblems).isEmpty()
    }

    @Test
    @DisplayName("Master 구간이면 진행률은 100%이며 다음 티어는 없다")
    fun `Master 진행률 계산`() {
        // given
        val bojId = "masterUser"
        val student = Student(
            id = "student-id",
            nickname = Nickname("masterUser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 3100,
            currentTier = Tier.fromRating(3100),
            role = Role.USER
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { quoteService.getRandomQuote() } returns null

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.currentTierTitle).isEqualTo("Master")
        assertThat(result.nextTierTitle).isEqualTo("Master")
        assertThat(result.requiredRatingForNextTier).isEqualTo(3000)
        assertThat(result.progressPercentage).isEqualTo(100)
    }
}
