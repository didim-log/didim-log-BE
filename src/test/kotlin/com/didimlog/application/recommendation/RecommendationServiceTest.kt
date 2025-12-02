package com.didimlog.application.recommendation

import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
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
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER, level = 6),
            createProblem(id = "p2", tier = Tier.SILVER, level = 7),
            createProblem(id = "p3", tier = Tier.SILVER, level = 8)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(6, 7) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended).allMatch { it.difficulty == Tier.SILVER }
        verify { problemRepository.findByLevelBetween(6, 7) }
    }

    @Test
    @DisplayName("이미 풀었던 문제는 추천 목록에서 제외된다")
    fun `이미 풀었던 문제는 제외`() {
        // given
        val bojId = "test123"
        val solvedProblemId = ProblemId("p1")
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf(solvedProblemId)
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER, level = 6),
            createProblem(id = "p2", tier = Tier.SILVER, level = 7),
            createProblem(id = "p3", tier = Tier.SILVER, level = 8)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(6, 7) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 10)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended.map { it.id.value }).doesNotContain("p1")
        assertThat(recommended.map { it.id.value }).containsExactlyInAnyOrder("p2", "p3")
    }

    @Test
    @DisplayName("풀 수 있는 문제가 없으면 빈 리스트를 반환한다")
    fun `풀 수 있는 문제가 없으면 빈 리스트 반환`() {
        // given
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(6, 7) } returns emptyList()

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 5)

        // then
        assertThat(recommended).isEmpty()
    }

    @Test
    @DisplayName("모든 후보 문제를 이미 풀었으면 빈 리스트를 반환한다")
    fun `모든 후보 문제를 이미 풀었으면 빈 리스트 반환`() {
        // given
        val bojId = "test123"
        val solvedProblemIds = setOf(ProblemId("p1"), ProblemId("p2"), ProblemId("p3"))
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = solvedProblemIds
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER, level = 6),
            createProblem(id = "p2", tier = Tier.SILVER, level = 7),
            createProblem(id = "p3", tier = Tier.SILVER, level = 8)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(6, 7) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 5)

        // then
        assertThat(recommended).isEmpty()
    }

    @Test
    @DisplayName("PLATINUM 티어 학생에게는 DIAMOND 난이도(level 21~22) 문제를 추천한다")
    fun `PLATINUM 티어 학생에게 DIAMOND 난이도 문제 추천`() {
        // given
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.PLATINUM,
            solvedProblemIds = setOf()
        )
        // PLATINUM 다음 티어인 DIAMOND의 minLevel(21) ~ minLevel+1(22) 문제를 추천
        val diamondProblems = listOf(
            createProblem(id = "p1", tier = Tier.DIAMOND, level = 21),
            createProblem(id = "p2", tier = Tier.DIAMOND, level = 22),
            createProblem(id = "p3", tier = Tier.DIAMOND, level = 23)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(21, 22) } returns diamondProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        // 무한 성장 로직이 난이도 레벨 범위 21~22(DIAMOND.minLevel ~ minLevel+1)를 조회하는지 검증
        verify { problemRepository.findByLevelBetween(21, 22) }
        assertThat(recommended).hasSize(2)
        assertThat(recommended).allMatch { it.difficulty == Tier.DIAMOND }
    }

    @Test
    @DisplayName("학생이 존재하지 않으면 예외가 발생한다")
    fun `학생이 없으면 예외 발생`() {
        // given
        val bojId = "nonexistent"

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.empty()

        // expect
        assertThatThrownBy {
            recommendationService.recommendProblems(bojId, count = 5)
        }.isInstanceOf(com.didimlog.global.exception.BusinessException::class.java)
            .hasMessageContaining("학생을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("요청한 개수보다 적은 문제가 있으면 모든 문제를 반환한다")
    fun `요청 개수보다 적으면 모든 문제 반환`() {
        // given
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )
        val silverProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER, level = 6),
            createProblem(id = "p2", tier = Tier.SILVER, level = 7)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(6, 7) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 10)

        // then
        assertThat(recommended).hasSize(2)
    }

    @Test
    @DisplayName("SILVER 티어 학생에게 GOLD 난이도 문제를 추천한다")
    fun `SILVER 티어 학생에게 GOLD 난이도 문제 추천`() {
        // given
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.SILVER,
            solvedProblemIds = setOf()
        )
        val goldProblems = listOf(
            createProblem(id = "p1", tier = Tier.GOLD, level = 11),
            createProblem(id = "p2", tier = Tier.GOLD, level = 12)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(11, 12) } returns goldProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended).allMatch { it.difficulty == Tier.GOLD }
        verify { problemRepository.findByLevelBetween(11, 12) }
    }

    private fun createStudent(
        id: String,
        bojId: String,
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
            bojId = BojId(bojId),
            password = "test-password",
            currentTier = tier,
            solutions = solutions
        )
    }

    private fun createProblem(id: String, tier: Tier, level: Int = tier.minLevel): Problem {
        return Problem(
            id = ProblemId(id),
            title = "Test Problem $id",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = tier,
            level = level,
            url = "https://www.acmicpc.net/problem/$id"
        )
    }
}

