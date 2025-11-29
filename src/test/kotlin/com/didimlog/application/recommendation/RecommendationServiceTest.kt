package com.didimlog.application.recommendation

import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

@DisplayName("RecommendationService 테스트")
class RecommendationServiceTest {

    private val studentRepository = mockk<StudentRepository>()
    private val problemRepository = mockk<ProblemRepository>()
    private val recommendationService = RecommendationService(studentRepository, problemRepository)

    @Test
    @DisplayName("BRONZE 티어 학생에게 SILVER 난이도 문제를 추천한다")
    fun `BRONZE 티어 학생에게 SILVER 난이도 문제 추천`() {
        // given
        val studentId = "student-1"
        val student = createStudent(
            id = studentId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER),
            createProblem(id = "p2", tier = Tier.SILVER),
            createProblem(id = "p3", tier = Tier.SILVER)
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(2, 2) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended).allMatch { it.difficulty == Tier.SILVER }
        verify { problemRepository.findByDifficultyLevelBetween(2, 2) }
    }

    @Test
    @DisplayName("이미 풀었던 문제는 추천 목록에서 제외된다")
    fun `이미 풀었던 문제는 제외`() {
        // given
        val studentId = "student-1"
        val solvedProblemId = ProblemId("p1")
        val student = createStudent(
            id = studentId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf(solvedProblemId)
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER),
            createProblem(id = "p2", tier = Tier.SILVER),
            createProblem(id = "p3", tier = Tier.SILVER)
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(2, 2) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 10)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended.map { it.id.value }).doesNotContain("p1")
        assertThat(recommended.map { it.id.value }).containsExactlyInAnyOrder("p2", "p3")
    }

    @Test
    @DisplayName("풀 수 있는 문제가 없으면 빈 리스트를 반환한다")
    fun `풀 수 있는 문제가 없으면 빈 리스트 반환`() {
        // given
        val studentId = "student-1"
        val student = createStudent(
            id = studentId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(2, 2) } returns emptyList()

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 5)

        // then
        assertThat(recommended).isEmpty()
    }

    @Test
    @DisplayName("모든 후보 문제를 이미 풀었으면 빈 리스트를 반환한다")
    fun `모든 후보 문제를 이미 풀었으면 빈 리스트 반환`() {
        // given
        val studentId = "student-1"
        val solvedProblemIds = setOf(ProblemId("p1"), ProblemId("p2"), ProblemId("p3"))
        val student = createStudent(
            id = studentId,
            tier = Tier.BRONZE,
            solvedProblemIds = solvedProblemIds
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER),
            createProblem(id = "p2", tier = Tier.SILVER),
            createProblem(id = "p3", tier = Tier.SILVER)
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(2, 2) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 5)

        // then
        assertThat(recommended).isEmpty()
    }

    @Test
    @DisplayName("PLATINUM 티어 학생에게는 DIAMOND 난이도(level 5) 문제를 추천한다")
    fun `PLATINUM 티어 학생에게 DIAMOND 난이도 문제 추천`() {
        // given
        val studentId = "student-1"
        val student = createStudent(
            id = studentId,
            tier = Tier.PLATINUM,
            solvedProblemIds = setOf()
        )
        // DIAMOND 난이도는 Tier Enum에 없지만, 난이도 레벨 5로 표현
        // 실제 구현에서는 Tier Enum 확장 또는 difficulty를 Int로 변경이 필요하지만,
        // 현재는 무한 성장 로직이 난이도 레벨 5를 조회하는지 검증하는 것이 목적
        // 주의: 현재 Problem 도메인은 difficulty가 Tier Enum이므로, 
        // difficultyLevel이 5인 문제를 직접 만들 수 없어 PLATINUM(level 4) 문제를 사용
        // 하지만 실제 DB에는 difficultyLevel이 5인 문제가 저장될 수 있으며,
        // 이 테스트는 무한 성장 로직이 올바른 난이도 레벨(5)을 조회하는지 검증한다
        val diamondProblems = listOf(
            createProblem(id = "p1", tier = Tier.PLATINUM),
            createProblem(id = "p2", tier = Tier.PLATINUM),
            createProblem(id = "p3", tier = Tier.PLATINUM)
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(5, 5) } returns diamondProblems

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 2)

        // then
        // 무한 성장 로직이 난이도 레벨 5를 조회하는지 검증
        verify { problemRepository.findByDifficultyLevelBetween(5, 5) }
        assertThat(recommended).hasSize(2)
    }

    @Test
    @DisplayName("학생이 존재하지 않으면 예외가 발생한다")
    fun `학생이 없으면 예외 발생`() {
        // given
        val studentId = "non-existent"

        every { studentRepository.findById(studentId) } returns Optional.empty()

        // expect
        assertThatThrownBy {
            recommendationService.recommendProblems(studentId, count = 5)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("학생을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("요청한 개수보다 적은 문제가 있으면 모든 문제를 반환한다")
    fun `요청 개수보다 적으면 모든 문제 반환`() {
        // given
        val studentId = "student-1"
        val student = createStudent(
            id = studentId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER),
            createProblem(id = "p2", tier = Tier.SILVER)
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(2, 2) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 10)

        // then
        assertThat(recommended).hasSize(2)
    }

    @Test
    @DisplayName("SILVER 티어 학생에게 GOLD 난이도 문제를 추천한다")
    fun `SILVER 티어 학생에게 GOLD 난이도 문제 추천`() {
        // given
        val studentId = "student-1"
        val student = createStudent(
            id = studentId,
            tier = Tier.SILVER,
            solvedProblemIds = setOf()
        )
        val goldProblems = listOf(
            createProblem(id = "p1", tier = Tier.GOLD),
            createProblem(id = "p2", tier = Tier.GOLD)
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findByDifficultyLevelBetween(3, 3) } returns goldProblems

        // when
        val recommended = recommendationService.recommendProblems(studentId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended).allMatch { it.difficulty == Tier.GOLD }
        verify { problemRepository.findByDifficultyLevelBetween(3, 3) }
    }

    private fun createStudent(
        id: String,
        tier: Tier,
        solvedProblemIds: Set<ProblemId>
    ): Student {
        val solutions = Solutions()
        solvedProblemIds.forEach { problemId ->
            solutions.add(
                Solution(
                    problemId = problemId,
                    timeTaken = TimeTakenSeconds(100L),
                    result = ProblemResult.SUCCESS
                )
            )
        }
        return Student(
            id = id,
            nickname = Nickname("test-user"),
            bojId = BojId("test123"),
            currentTier = tier,
            solutions = solutions
        )
    }

    private fun createProblem(id: String, tier: Tier): Problem {
        return Problem(
            id = ProblemId(id),
            title = "Test Problem $id",
            category = "TEST",
            difficulty = tier,
            url = "https://www.acmicpc.net/problem/$id"
        )
    }
}

