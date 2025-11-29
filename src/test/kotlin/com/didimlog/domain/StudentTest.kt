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
    @DisplayName("solveProblem은 문제 풀이 결과를 Solutions에 추가한다")
    fun `solveProblem으로 풀이 기록 추가`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.BRONZE
        )
        val problem = Problem(
            id = ProblemId("p-1"),
            title = "A+B",
            category = "IMPLEMENTATION",
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
    @DisplayName("updateTier는 외부에서 가져온 티어 정보로 티어를 업데이트한다")
    fun `updateTier로 티어 업데이트`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.BRONZE
        )

        // when
        val updatedStudent = student.updateTier(Tier.GOLD)

        // then
        assertThat(updatedStudent.tier()).isEqualTo(Tier.GOLD)
    }

    @Test
    @DisplayName("getSolvedProblemIds는 풀이한 문제 ID 목록을 반환한다")
    fun `getSolvedProblemIds로 풀이한 문제 ID 조회`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.BRONZE
        )
        val problem1 = Problem(
            id = ProblemId("p-1"),
            title = "Problem 1",
            category = "TEST",
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1"
        )
        val problem2 = Problem(
            id = ProblemId("p-2"),
            title = "Problem 2",
            category = "TEST",
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
}


