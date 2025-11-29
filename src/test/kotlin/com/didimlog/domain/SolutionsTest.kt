package com.didimlog.domain

import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@DisplayName("Solutions 일급 컬렉션 테스트")
class SolutionsTest {

    @Test
    @DisplayName("최근 10문제 중 8문제를 성공하면 성공률은 0.8이다")
    fun `최근 10문제 중 8문제 성공시 0_8 반환`() {
        // given
        val solutions = Solutions()
        val baseTime = LocalDateTime.now()

        // 최근 10문제 중 앞 2개는 FAIL, 나머지 8개는 SUCCESS
        (1..2).forEach { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("problem-$index"),
                    timeTaken = TimeTakenSeconds(100L * index),
                    result = ProblemResult.FAIL,
                    solvedAt = baseTime.minusMinutes((10 - index).toLong())
                )
            )
        }
        (3..10).forEach { index ->
            solutions.add(
                Solution(
                    problemId = ProblemId("problem-$index"),
                    timeTaken = TimeTakenSeconds(100L * index),
                    result = ProblemResult.SUCCESS,
                    solvedAt = baseTime.minusMinutes((10 - index).toLong())
                )
            )
        }

        // when
        val successRate = solutions.calculateRecentSuccessRate()

        // then
        assertThat(successRate).isEqualTo(0.8)
    }

    @Test
    @DisplayName("풀이 기록이 없으면 성공률은 0_0이다")
    fun `풀이 기록이 없으면 0_0 반환`() {
        // given
        val solutions = Solutions()

        // when
        val successRate = solutions.calculateRecentSuccessRate()

        // then
        assertThat(successRate).isEqualTo(0.0)
    }

    @Test
    @DisplayName("풀이 기록이 limit보다 적어도 전체를 기준으로 성공률을 계산한다")
    fun `limit보다 적은 풀이 기록에 대한 성공률 계산`() {
        // given
        val solutions = Solutions()

        solutions.add(
            Solution(
                ProblemId("p1"),
                TimeTakenSeconds(100L),
                ProblemResult.SUCCESS
            )
        )
        solutions.add(
            Solution(
                ProblemId("p2"),
                TimeTakenSeconds(120L),
                ProblemResult.FAIL
            )
        )
        solutions.add(
            Solution(
                ProblemId("p3"),
                TimeTakenSeconds(130L),
                ProblemResult.SUCCESS
            )
        )

        // when
        val successRate = solutions.calculateRecentSuccessRate(limit = 10)

        // then
        assertThat(successRate).isEqualTo(2.0 / 3.0)
    }

    @Test
    @DisplayName("limit 값에 따라 최근 풀이만 기준으로 성공률을 계산한다")
    fun `limit에 따라 최근 풀이 기준 성공률 계산`() {
        // given
        val solutions = Solutions()

        // 총 5문제 중 앞 3개는 FAIL, 뒤 2개는 SUCCESS
        (1..3).forEach { index ->
            solutions.add(
                Solution(
                    ProblemId("problem-$index"),
                    TimeTakenSeconds(100L * index),
                    ProblemResult.FAIL
                )
            )
        }
        (4..5).forEach { index ->
            solutions.add(
                Solution(
                    ProblemId("problem-$index"),
                    TimeTakenSeconds(100L * index),
                    ProblemResult.SUCCESS
                )
            )
        }

        // when: limit=2 이므로 최근 2문제(SUCCESS, SUCCESS)만 기준
        val successRate = solutions.calculateRecentSuccessRate(limit = 2)

        // then
        assertThat(successRate).isEqualTo(1.0)
    }
}

