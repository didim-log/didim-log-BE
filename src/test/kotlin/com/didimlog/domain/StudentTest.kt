package com.didimlog.domain

import com.didimlog.domain.enums.*
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.study.domain.ProblemResult
import com.didimlog.study.domain.TimeTakenSeconds
import com.didimlog.template.domain.TemplateCategory
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
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
    fun `문제 풀이를 제거하면 남은 기록으로 마지막 풀이일과 연속 일수를 다시 계산한다`() {
        val targetProblem = Problem(
            id = ProblemId("remove-target"),
            title = "Remove Target",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/remove-target"
        )
        val oldProblem = targetProblem.copy(id = ProblemId("old-record"))
        val firstRemainingProblem = targetProblem.copy(id = ProblemId("remaining-1"))
        val secondRemainingProblem = targetProblem.copy(id = ProblemId("remaining-2"))
        val thirdRemainingProblem = targetProblem.copy(id = ProblemId("remaining-3"))
        val latestSolvedAt = LocalDateTime.of(2026, 7, 30, 10, 0)
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
            .solveProblem(oldProblem, TimeTakenSeconds(100), true, latestSolvedAt.minusDays(6))
            .solveProblem(targetProblem, TimeTakenSeconds(100), true, latestSolvedAt.minusDays(4))
            .solveProblem(firstRemainingProblem, TimeTakenSeconds(100), true, latestSolvedAt.minusDays(3))
            .solveProblem(secondRemainingProblem, TimeTakenSeconds(100), true, latestSolvedAt.minusDays(2))
            .solveProblem(thirdRemainingProblem, TimeTakenSeconds(100), true, latestSolvedAt.minusDays(1))
            .solveProblem(targetProblem, TimeTakenSeconds(100), true, latestSolvedAt)

        val targetRemoved = student.removeSolutionsByProblemId(targetProblem.id)
        val allRemoved = targetRemoved
            .removeSolutionsByProblemId(oldProblem.id)
            .removeSolutionsByProblemId(firstRemainingProblem.id)
            .removeSolutionsByProblemId(secondRemainingProblem.id)
            .removeSolutionsByProblemId(thirdRemainingProblem.id)

        assertThat(targetRemoved.getSolvedProblemIds()).containsExactly(
            oldProblem.id,
            firstRemainingProblem.id,
            secondRemainingProblem.id,
            thirdRemainingProblem.id
        )
        assertThat(targetRemoved.lastSolvedAt).isEqualTo(latestSolvedAt.toLocalDate().minusDays(1))
        assertThat(targetRemoved.consecutiveSolveDays).isEqualTo(3)
        assertThat(allRemoved.solutions.getAll()).isEmpty()
        assertThat(allRemoved.lastSolvedAt).isNull()
        assertThat(allRemoved.consecutiveSolveDays).isZero()
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

    @Test
    @DisplayName("기본 템플릿 ID가 사용되는 성공·실패 카테고리를 반환한다")
    fun `기본 템플릿 카테고리 조회`() {
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            currentTier = Tier.BRONZE,
            defaultSuccessTemplateId = "shared-template",
            defaultFailTemplateId = "shared-template"
        )

        assertThat(student.defaultTemplateCategories("shared-template"))
            .containsExactlyInAnyOrder(TemplateCategory.SUCCESS, TemplateCategory.FAIL)
        assertThat(student.defaultTemplateCategories("other-template")).isEmpty()
    }

    @Test
    @DisplayName("저장소에서 누락 필드가 있는 레거시 문서를 읽으면 안전한 기본값으로 채운다")
    fun `레거시 문서 기본값 보정`() {
        val student = Student(
            id = "legacy",
            nickname = Nickname("legacy"),
            provider = Provider.GOOGLE,
            providerId = "google-1",
            email = null,
            bojId = null,
            password = null,
            credentialVersion = null,
            rating = 1600,
            solvedAcTierLevel = null,
            currentTier = Tier.GOLD,
            role = null,
            termsAgreed = null,
            isVerified = null,
            solutions = null,
            consecutiveSolveDays = null,
            lastSolvedAt = null,
            primaryLanguage = null,
            isOnboardingFinished = null,
            defaultSuccessTemplateId = null,
            defaultFailTemplateId = null,
            createdAt = null,
            documentVersion = null
        )

        assertThat(student.credentialVersion).isZero()
        assertThat(student.solvedAcTierLevel).isEqualTo(SolvedAcTierLevel.fromRating(1600))
        assertThat(student.role).isEqualTo(Role.GUEST)
        assertThat(student.termsAgreed).isFalse()
        assertThat(student.isVerified).isFalse()
        assertThat(student.solutions.getAll()).isEmpty()
        assertThat(student.consecutiveSolveDays).isZero()
        assertThat(student.isOnboardingFinished).isFalse()
        assertThat(student.createdAt).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS))
        assertThat(student.documentVersion).isNull()
    }

    @Test
    @DisplayName("레거시 문서에 rating도 없으면 0점 기준으로 Solved.ac 티어 레벨을 계산한다")
    fun `레거시 문서 rating 누락`() {
        val student = Student(
            id = "legacy", nickname = Nickname("legacy"), provider = Provider.BOJ, providerId = "p",
            email = null, bojId = null, password = null, credentialVersion = 2L, rating = null,
            solvedAcTierLevel = null, currentTier = Tier.BRONZE, role = Role.USER, termsAgreed = true,
            isVerified = true, solutions = null, consecutiveSolveDays = 3, lastSolvedAt = null,
            primaryLanguage = PrimaryLanguage.KOTLIN, isOnboardingFinished = true,
            defaultSuccessTemplateId = "s", defaultFailTemplateId = "f",
            createdAt = LocalDateTime.of(2025, 1, 1, 0, 0), documentVersion = 7L
        )

        assertThat(student.rating).isZero()
        assertThat(student.solvedAcTierLevel).isEqualTo(SolvedAcTierLevel(0))
        assertThat(student.credentialVersion).isEqualTo(2L)
        assertThat(student.role).isEqualTo(Role.USER)
        assertThat(student.createdAt).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0))
        assertThat(student.documentVersion).isEqualTo(7L)
    }

    @Test
    @DisplayName("닉네임 변경은 닉네임 정책을 적용하고 비밀번호 변경은 빈 값을 거부한다")
    fun `닉네임과 비밀번호 검증`() {
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            password = null,
            currentTier = Tier.BRONZE
        )

        assertThat(student.updateNickname("renamed").nickname).isEqualTo(Nickname("renamed"))
        assertThatThrownBy { student.updateNickname("admin") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { student.updatePassword(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("비밀번호는 필수입니다.")
    }

    @Test
    @DisplayName("비밀번호가 없는 소셜 계정은 인코더 호출 없이 비밀번호 불일치로 판단한다")
    fun `비밀번호 없는 계정 매칭`() {
        val student = Student(
            nickname = Nickname("social"),
            provider = Provider.GOOGLE,
            providerId = "g-1",
            password = null,
            currentTier = Tier.BRONZE
        )
        val encoder = io.mockk.mockk<org.springframework.security.crypto.password.PasswordEncoder>()

        assertThat(student.matchPassword("anything", encoder)).isFalse()
        io.mockk.verify(exactly = 0) { encoder.matches(any(), any()) }
    }
}
