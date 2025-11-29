package com.didimlog.domain

import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Student 도메인 테스트")
class StudentTest {

    @Test
    @DisplayName("최근 10문제 중 8문제가 성공이면 문제를 성공했을 때 티어가 1단계 상승한다")
    fun `최근 10문제 성공률이 80 퍼센트 이상이면 티어 승급`() {
        // given
        val solutions = Solutions()
        repeat(7) { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("success-$index"),
                    timeTaken = TimeTakenSeconds(100L),
                    result = ProblemResult.SUCCESS
                )
            )
        }
        repeat(2) { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("fail-$index"),
                    timeTaken = TimeTakenSeconds(100L),
                    result = ProblemResult.FAIL
                )
            )
        }
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.BRONZE,
            solutions = solutions
        )
        val problem = Problem(
            id = ProblemId("p-1"),
            title = "A+B",
            category = "IMPLEMENTATION",
            difficulty = Tier.BRONZE,
            url = "https://www.acmicpc.net/problem/1000"
        )

        // when: 10번째 문제를 성공적으로 풀면 8/10 성공이 된다.
        student.solveProblem(
            problem = problem,
            timeTakenSeconds = TimeTakenSeconds(120L),
            isSuccess = true
        )

        // then
        assertThat(student.tier()).isEqualTo(Tier.SILVER)
    }

    @Test
    @DisplayName("최근 10문제 성공률이 80퍼센트 미만이면 티어가 상승하지 않는다")
    fun `성공률이 부족하면 티어 유지`() {
        // given
        val solutions = Solutions()
        repeat(6) { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("success-$index"),
                    timeTaken = TimeTakenSeconds(100L),
                    result = ProblemResult.SUCCESS
                )
            )
        }
        repeat(3) { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("fail-$index"),
                    timeTaken = TimeTakenSeconds(100L),
                    result = ProblemResult.FAIL
                )
            )
        }
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.BRONZE,
            solutions = solutions
        )
        val problem = Problem(
            id = ProblemId("p-1"),
            title = "A+B",
            category = "IMPLEMENTATION",
            difficulty = Tier.BRONZE,
            url = "https://www.acmicpc.net/problem/1000"
        )

        // when: 10번째 문제를 성공해도 7/10 성공률이므로 승급 조건 미달
        student.solveProblem(
            problem = problem,
            timeTakenSeconds = TimeTakenSeconds(120L),
            isSuccess = true
        )

        // then
        assertThat(student.tier()).isEqualTo(Tier.BRONZE)
    }

    @Test
    @DisplayName("PLATINUM 티어인 경우 성공률이 높아도 더 이상 티어가 오르지 않는다")
    fun `최대 티어에서는 승급하지 않는다`() {
        // given
        val solutions = Solutions()
        repeat(9) { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("success-$index"),
                    timeTaken = TimeTakenSeconds(100L),
                    result = ProblemResult.SUCCESS
                )
            )
        }
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.PLATINUM,
            solutions = solutions
        )
        val problem = Problem(
            id = ProblemId("p-1"),
            title = "A+B",
            category = "IMPLEMENTATION",
            difficulty = Tier.PLATINUM,
            url = "https://www.acmicpc.net/problem/1000"
        )

        // when
        student.solveProblem(
            problem = problem,
            timeTakenSeconds = TimeTakenSeconds(120L),
            isSuccess = true
        )

        // then
        assertThat(student.tier()).isEqualTo(Tier.PLATINUM)
    }
}


