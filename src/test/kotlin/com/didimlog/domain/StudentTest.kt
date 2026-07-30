package com.didimlog.domain

import com.didimlog.domain.enums.*
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Student 도메인 테스트")
class StudentTest {

    @Test
    @DisplayName("solveProblem은 문제 풀이 결과를 Solutions에 추가한다")
    fun `solveProblem으로 풀이 기록 추가`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val problem = Problem(
            id = ProblemId("p-1"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        // when
        val updatedStudent = student.solveProblem(
            problem = problem,
            timeTakenSeconds = TimeTakenSeconds(120L),
            isSuccess = true
        )

        // then
        val solvedProblemIds = updatedStudent.getSolvedProblemIds()
        assertThat(solvedProblemIds).contains(ProblemId("p-1"))
        assertThat(updatedStudent.tier()).isEqualTo(Tier.BRONZE) // 티어는 변경되지 않음 (외부 동기화 방식)
    }

    @Test
    fun `같은 문제를 같은 날 다시 제출하면 고정된 제출 시각으로 한 건을 교체한다`() {
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val problem = Problem(
            id = ProblemId("same-day"),
            title = "Same Day",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/same-day"
        )
        val firstSubmittedAt = LocalDateTime.of(2026, 7, 30, 10, 0)
            .withNano(123_456_789)
        val secondSubmittedAt = firstSubmittedAt.plusHours(1)

        val first = student.solveProblem(
            problem,
            TimeTakenSeconds(120),
            isSuccess = false,
            solvedAt = firstSubmittedAt
        )
        val second = first.solveProblem(
            problem,
            TimeTakenSeconds(90),
            isSuccess = true,
            solvedAt = secondSubmittedAt
        )
        val staleRetry = second.solveProblem(
            problem,
            TimeTakenSeconds(300),
            isSuccess = false,
            solvedAt = firstSubmittedAt.plusMinutes(30)
        )

        assertThat(staleRetry.solutions.getAll()).hasSize(1)
        val solution = staleRetry.solutions.getAll().single()
        assertThat(solution.timeTaken).isEqualTo(TimeTakenSeconds(90))
        assertThat(solution.result).isEqualTo(ProblemResult.SUCCESS)
        assertThat(solution.solvedAt).isEqualTo(secondSubmittedAt.truncatedTo(ChronoUnit.MILLIS))

        val sameMillisecondFirst = student.solveProblem(
            problem,
            TimeTakenSeconds(70),
            isSuccess = true,
            solvedAt = firstSubmittedAt
        )
        val sameMillisecondRetry = sameMillisecondFirst.solveProblem(
            problem,
            TimeTakenSeconds(80),
            isSuccess = false,
            solvedAt = firstSubmittedAt.plusNanos(500_000)
        )
        val sameMillisecondSolution = sameMillisecondRetry.solutions.getAll().single()
        assertThat(sameMillisecondSolution.timeTaken).isEqualTo(TimeTakenSeconds(70))
        assertThat(sameMillisecondSolution.result).isEqualTo(ProblemResult.SUCCESS)
    }

    @Test
    fun `연속 풀이 일수는 같은 날 유지하고 다음 날 증가하며 공백 뒤 초기화한다`() {
        val problem = Problem(
            id = ProblemId("streak"),
            title = "Streak",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/streak"
        )
        val submittedAt = LocalDateTime.of(2026, 7, 30, 10, 0)
        val baseStudent = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        val nextDay = baseStudent.copy(
            consecutiveSolveDays = 4,
            lastSolvedAt = submittedAt.toLocalDate().minusDays(1)
        ).solveProblem(problem, TimeTakenSeconds(100), true, submittedAt)
        val sameDay = nextDay.solveProblem(
            problem.copy(id = ProblemId("streak-2")),
            TimeTakenSeconds(100),
            true,
            submittedAt.plusHours(1)
        )
        val afterGap = baseStudent.copy(
            consecutiveSolveDays = 9,
            lastSolvedAt = submittedAt.toLocalDate().minusDays(2)
        ).solveProblem(problem, TimeTakenSeconds(100), true, submittedAt)
        val previousDayRetry = sameDay.solveProblem(
            problem.copy(id = ProblemId("streak-previous")),
            TimeTakenSeconds(100),
            true,
            submittedAt.minusDays(1)
        )
        val twoDaysAgo = baseStudent.solveProblem(
            problem.copy(id = ProblemId("streak-day-zero")),
            TimeTakenSeconds(100),
            true,
            submittedAt.minusDays(2)
        )
        val todayBeforeYesterday = twoDaysAgo.solveProblem(
            problem.copy(id = ProblemId("streak-day-two")),
            TimeTakenSeconds(100),
            true,
            submittedAt
        )
        val bridgedDays = todayBeforeYesterday.solveProblem(
            problem.copy(id = ProblemId("streak-day-one")),
            TimeTakenSeconds(100),
            true,
            submittedAt.minusDays(1)
        )

        assertThat(nextDay.consecutiveSolveDays).isEqualTo(5)
        assertThat(sameDay.consecutiveSolveDays).isEqualTo(5)
        assertThat(afterGap.consecutiveSolveDays).isEqualTo(1)
        assertThat(previousDayRetry.consecutiveSolveDays).isEqualTo(5)
        assertThat(previousDayRetry.lastSolvedAt).isEqualTo(submittedAt.toLocalDate())
        assertThat(previousDayRetry.solutions.getAll().map { it.solvedAt })
            .isSorted()
        assertThat(todayBeforeYesterday.consecutiveSolveDays).isEqualTo(1)
        assertThat(bridgedDays.consecutiveSolveDays).isEqualTo(3)
        assertThat(bridgedDays.lastSolvedAt).isEqualTo(submittedAt.toLocalDate())
    }

    @Test
    @DisplayName("updateInfo는 외부에서 가져온 Rating 점수로 티어를 업데이트한다")
    fun `updateInfo로 티어 업데이트`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        // when: Solved.ac API에서 가져온 Rating 점수로 업데이트 (GOLD 티어는 800점 이상)
        val updatedStudent = student.updateInfo(800)

        // then
        assertThat(updatedStudent.tier()).isEqualTo(Tier.GOLD)
    }

    @Test
    @DisplayName("getSolvedProblemIds는 풀이한 문제 ID 목록을 반환한다")
    fun `getSolvedProblemIds로 풀이한 문제 ID 조회`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val problem1 = Problem(
            id = ProblemId("p-1"),
            title = "Problem 1",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1"
        )
        val problem2 = Problem(
            id = ProblemId("p-2"),
            title = "Problem 2",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 4,
            url = "https://www.acmicpc.net/problem/2"
        )

        // when
        val studentAfterFirst = student.solveProblem(problem1, TimeTakenSeconds(100L), isSuccess = true)
        val studentAfterSecond = studentAfterFirst.solveProblem(problem2, TimeTakenSeconds(120L), isSuccess = false)

        // then
        val solvedProblemIds = studentAfterSecond.getSolvedProblemIds()
        assertThat(solvedProblemIds).containsExactlyInAnyOrder(ProblemId("p-1"), ProblemId("p-2"))
    }

    @Test
    @DisplayName("updatePrimaryLanguage는 새로운 언어로 Student를 업데이트한다")
    fun `primaryLanguage 업데이트 성공`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            primaryLanguage = null
        )

        // when
        val updated = student.updatePrimaryLanguage(PrimaryLanguage.JAVA)

        // then
        assertThat(updated.primaryLanguage).isEqualTo(PrimaryLanguage.JAVA)
        assertThat(student.primaryLanguage).isNull() // 원본 불변 확인
    }

    @Test
    @DisplayName("updatePrimaryLanguage는 기존 언어를 새로운 언어로 변경할 수 있다")
    fun `primaryLanguage 변경 성공`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            primaryLanguage = PrimaryLanguage.PYTHON
        )

        // when
        val updated = student.updatePrimaryLanguage(PrimaryLanguage.KOTLIN)

        // then
        assertThat(updated.primaryLanguage).isEqualTo(PrimaryLanguage.KOTLIN)
        assertThat(student.primaryLanguage).isEqualTo(PrimaryLanguage.PYTHON) // 원본 불변 확인
    }

    @Test
    @DisplayName("updatePassword는 비밀번호와 자격 증명 버전을 함께 갱신한다")
    fun `비밀번호 갱신 시 자격 증명 버전 증가`() {
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "old-password",
            credentialVersion = 3,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        val updated = student.updatePassword("new-password")

        assertThat(updated.password).isEqualTo("new-password")
        assertThat(updated.credentialVersion).isEqualTo(4)
        assertThat(student.password).isEqualTo("old-password")
        assertThat(student.credentialVersion).isEqualTo(3)
    }
}
