package com.didimlog.application.dashboard

import com.didimlog.application.quote.QuoteService
import com.didimlog.domain.Quote
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
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
    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val quoteService: QuoteService = mockk()

    private val dashboardService = DashboardService(
        studentRepository,
        retrospectiveRepository,
        quoteService
    )

    @Test
    @DisplayName("오늘 작성된 회고가 제대로 포함되는지 검증한다")
    fun `오늘 푼 문제 포함 검증`() {
        // given
        val bojId = "testuser"
        val studentId = "student-id"
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay()
        val endOfDay = today.atTime(23, 59, 59)

        val todayRetrospective1 = Retrospective(
            id = "retro-1",
            studentId = studentId,
            problemId = "1000",
            content = "이 문제는 DFS를 사용해서 풀었습니다. 매우 재미있었습니다.",
            solutionResult = ProblemResult.SUCCESS,
            createdAt = LocalDateTime.now()
        )
        val todayRetrospective2 = Retrospective(
            id = "retro-2",
            studentId = studentId,
            problemId = "1001",
            content = "이 문제는 Greedy 알고리즘으로 접근했지만 실패했습니다.",
            solutionResult = ProblemResult.FAIL,
            createdAt = LocalDateTime.now().minusHours(2)
        )

        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            rating = 850,
            currentTier = Tier.fromRating(850),
            role = Role.USER,
            consecutiveSolveDays = 5
        )

        val quote = Quote(content = "테스트 명언", author = "테스트 작가")

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every {
            retrospectiveRepository.findByStudentIdAndCreatedAtBetween(
                studentId,
                startOfDay,
                endOfDay
            )
        } returns listOf(todayRetrospective1, todayRetrospective2)
        every { quoteService.getRandomQuote() } returns quote

        // when
        val result = dashboardService.getDashboard(bojId)

        // then
        assertThat(result.todaySolvedCount).isEqualTo(2)
        assertThat(result.todaySolvedProblems).hasSize(2)
        assertThat(result.todaySolvedProblems.map { it.problemId }).containsExactlyInAnyOrder("1000", "1001")
        assertThat(result.todaySolvedProblems.map { it.result }).containsExactlyInAnyOrder("SUCCESS", "FAIL")
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
    @DisplayName("오늘 작성된 회고가 없으면 빈 리스트를 반환한다")
    fun `오늘 푼 문제 없음`() {
        // given
        val bojId = "testuser"
        val studentId = "student-id"
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay()
        val endOfDay = today.atTime(23, 59, 59)

        val student = Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        val quote = Quote(content = "테스트 명언", author = "테스트 작가")

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every {
            retrospectiveRepository.findByStudentIdAndCreatedAtBetween(
                studentId,
                startOfDay,
                endOfDay
            )
        } returns emptyList()
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
        val studentId = "student-id"
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay()
        val endOfDay = today.atTime(23, 59, 59)

        val student = Student(
            id = studentId,
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
        every {
            retrospectiveRepository.findByStudentIdAndCreatedAtBetween(
                studentId,
                startOfDay,
                endOfDay
            )
        } returns emptyList()
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
