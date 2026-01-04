package com.didimlog.application.recommendation

import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
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
    @DisplayName("BRONZE 티어 학생에게 현재 티어 레벨 범위 -2 ~ +2 단계 문제를 추천한다")
    fun `BRONZE 티어 학생에게 -2~+2 레벨 범위 문제 추천`() {
        // given
        // BRONZE 티어(레벨 1~5) 학생 -> 레벨 (1-2) ~ (5+2) = 레벨 1~7 문제 추천
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )
        val recommendedProblems = listOf(
            createProblem(id = "p1", tier = Tier.BRONZE, level = 1),
            createProblem(id = "p2", tier = Tier.BRONZE, level = 3),
            createProblem(id = "p3", tier = Tier.SILVER, level = 6),
            createProblem(id = "p4", tier = Tier.SILVER, level = 7)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(1, 7) } returns recommendedProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended.map { it.level }).allMatch { it in 1..7 }
        verify { problemRepository.findByLevelBetween(1, 7) }
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
        // BRONZE 티어(레벨 1~5) -> 레벨 1~7 범위
        every { problemRepository.findByLevelBetween(1, 7) } returns silverProblems

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
        // BRONZE 티어(레벨 1~5) -> 레벨 1~7 범위
        every { problemRepository.findByLevelBetween(1, 7) } returns emptyList()

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
        // BRONZE 티어(레벨 1~5) -> 레벨 1~7 범위
        every { problemRepository.findByLevelBetween(1, 7) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 5)

        // then
        assertThat(recommended).isEmpty()
    }

    @Test
    @DisplayName("PLATINUM 티어 학생에게는 현재 티어 레벨 범위 -2 ~ +2 단계 문제를 추천한다")
    fun `PLATINUM 티어 학생에게 -2~+2 레벨 범위 문제 추천`() {
        // given
        // PLATINUM 티어(레벨 16~20) 학생 -> 레벨 (16-2) ~ (20+2) = 레벨 14~22 문제 추천
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.PLATINUM,
            solvedProblemIds = setOf()
        )
        val recommendedProblems = listOf(
            createProblem(id = "p1", tier = Tier.GOLD, level = 14),
            createProblem(id = "p2", tier = Tier.PLATINUM, level = 18),
            createProblem(id = "p3", tier = Tier.DIAMOND, level = 21),
            createProblem(id = "p4", tier = Tier.DIAMOND, level = 22)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(14, 22) } returns recommendedProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        verify { problemRepository.findByLevelBetween(14, 22) }
        assertThat(recommended).hasSize(2)
        assertThat(recommended.map { it.level }).allMatch { it in 14..22 }
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
        // BRONZE 티어(레벨 1~5) -> 레벨 1~7 범위
        every { problemRepository.findByLevelBetween(1, 7) } returns silverProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 10)

        // then
        assertThat(recommended).hasSize(2)
    }

    @Test
    @DisplayName("SILVER 티어 학생에게 현재 티어 레벨 범위 -2 ~ +2 단계 문제를 추천한다")
    fun `SILVER 티어 학생에게 -2~+2 레벨 범위 문제 추천`() {
        // given
        // SILVER 티어(레벨 6~10) 학생 -> 레벨 (6-2) ~ (10+2) = 레벨 4~12 문제 추천
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.SILVER,
            solvedProblemIds = setOf()
        )
        val recommendedProblems = listOf(
            createProblem(id = "p1", tier = Tier.BRONZE, level = 4),
            createProblem(id = "p2", tier = Tier.SILVER, level = 8),
            createProblem(id = "p3", tier = Tier.GOLD, level = 11),
            createProblem(id = "p4", tier = Tier.GOLD, level = 12)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(4, 12) } returns recommendedProblems

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended.map { it.level }).allMatch { it in 4..12 }
        verify { problemRepository.findByLevelBetween(4, 12) }
    }

    @Test
    @DisplayName("카테고리 필터가 정확히 동작한다")
    fun `카테고리 필터 동작 검증`() {
        // given
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )
        val implementationProblems = listOf(
            createProblem(id = "p1", tier = Tier.SILVER, level = 6, category = ProblemCategory.IMPLEMENTATION),
            createProblem(id = "p2", tier = Tier.SILVER, level = 7, category = ProblemCategory.IMPLEMENTATION)
        )
        val graphProblems = listOf(
            createProblem(id = "p3", tier = Tier.SILVER, level = 6, category = ProblemCategory.GRAPH_THEORY)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        // RecommendationService는 category를 englishName으로 변환하므로, 실제 호출되는 값으로 모킹
        // "IMPLEMENTATION"은 "Implementation"으로 변환되어 호출됨
        // "Graph Theory"는 그대로 "Graph Theory"로 호출됨 (englishName과 일치)
        // BRONZE 티어(레벨 1~5) -> 레벨 1~7 범위
        every { problemRepository.findByLevelBetweenAndCategory(1, 7, ProblemCategory.IMPLEMENTATION.englishName) } returns implementationProblems
        every { problemRepository.findByLevelBetweenAndCategory(1, 7, ProblemCategory.GRAPH_THEORY.englishName) } returns graphProblems

        // when
        // RecommendationService는 category를 englishName으로 변환하므로, 실제로는 "Implementation"과 "Graph Theory"로 변환됨
        val recommendedImplementation = recommendationService.recommendProblems(bojId, count = 5, category = "IMPLEMENTATION")
        val recommendedGraph = recommendationService.recommendProblems(bojId, count = 5, category = ProblemCategory.GRAPH_THEORY.englishName)

        // then
        assertThat(recommendedImplementation).hasSize(2)
        assertThat(recommendedImplementation).allMatch { it.category == ProblemCategory.IMPLEMENTATION }
        assertThat(recommendedGraph).hasSize(1)
        assertThat(recommendedGraph).allMatch { it.category == ProblemCategory.GRAPH_THEORY }
        // 카테고리 필터가 정확히 동작하는지 결과로 검증 (MockK verify는 제외)
    }

    @Test
    @DisplayName("카테고리 필터 적용 시 해당 카테고리 문제가 없으면 빈 리스트를 반환한다")
    fun `카테고리 필터 적용 시 문제 없으면 빈 리스트 반환`() {
        // given
        val bojId = "test123"
        val student = createStudent(
            id = "student-1",
            bojId = bojId,
            tier = Tier.BRONZE,
            solvedProblemIds = setOf()
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        // BRONZE 티어(레벨 1~5) -> 레벨 1~7 범위
        every { problemRepository.findByLevelBetweenAndCategory(1, 7, "DYNAMIC_PROGRAMMING") } returns emptyList()

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 5, category = "DYNAMIC_PROGRAMMING")

        // then
        assertThat(recommended).isEmpty()
        verify { problemRepository.findByLevelBetweenAndCategory(1, 7, "DYNAMIC_PROGRAMMING") }
    }

    @Test
    @DisplayName("UNRATED 티어 학생에게 Bronze V(레벨 1) ~ Bronze IV(레벨 2) 문제를 추천한다")
    fun `UNRATED 티어 학생에게 Bronze V~IV 레벨 문제 추천`() {
        // given
        // UNRATED 티어(레벨 0) 학생 -> 레벨 1~2 (Bronze V ~ Bronze IV) 문제 추천
        val bojId = "unrated123"
        val student = createStudent(
            id = "student-unrated",
            bojId = bojId,
            tier = Tier.UNRATED,
            solvedProblemIds = setOf()
        )
        val bronzeProblems = listOf(
            createProblem(id = "p1", tier = Tier.BRONZE, level = 1),
            createProblem(id = "p2", tier = Tier.BRONZE, level = 2),
            createProblem(id = "p3", tier = Tier.BRONZE, level = 3)
        )

        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { problemRepository.findByLevelBetween(1, 2) } returns bronzeProblems.filter { it.level in 1..2 }

        // when
        val recommended = recommendationService.recommendProblems(bojId, count = 2)

        // then
        assertThat(recommended).hasSize(2)
        assertThat(recommended.map { it.level }).allMatch { it in 1..2 }
        verify { problemRepository.findByLevelBetween(1, 2) }
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
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "test-password",
            currentTier = tier,
            role = Role.USER,
            solutions = solutions
        )
    }

    private fun createProblem(
        id: String,
        tier: Tier,
        level: Int = tier.minLevel,
        category: ProblemCategory = ProblemCategory.IMPLEMENTATION
    ): Problem {
        return Problem(
            id = ProblemId(id),
            title = "Test Problem $id",
            category = category,
            difficulty = tier,
            level = level,
            url = "https://www.acmicpc.net/problem/$id"
        )
    }
}

