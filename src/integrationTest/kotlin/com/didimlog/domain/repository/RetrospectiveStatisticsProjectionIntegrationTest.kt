package com.didimlog.domain.repository

import com.didimlog.domain.Retrospective
import com.didimlog.domain.enums.ProblemResult
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest

@DisplayName("Retrospective 통계 projection 통합 테스트")
@DataMongoTest
class RetrospectiveStatisticsProjectionIntegrationTest {

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @BeforeEach
    fun setUp() {
        retrospectiveRepository.deleteAll()
    }

    @Test
    @DisplayName("통계에 필요한 필드와 nullable 값을 projection으로 매핑한다")
    fun mapsStatisticsProjectionFieldsAndNullableValues() {
        val createdAt = LocalDateTime.of(2025, 6, 15, 12, 30)
        retrospectiveRepository.saveAll(
            listOf(
                retrospective(
                    studentId = "student-1",
                    problemId = "1000",
                    createdAt = createdAt,
                    result = ProblemResult.SUCCESS,
                    solvedCategory = "DP, Graph"
                ),
                retrospective(
                    studentId = "student-1",
                    problemId = "1001",
                    createdAt = createdAt.plusDays(1),
                    result = null,
                    solvedCategory = null
                ),
                retrospective(
                    studentId = "student-2",
                    problemId = "2000",
                    createdAt = createdAt,
                    result = ProblemResult.FAIL,
                    solvedCategory = "Greedy"
                )
            )
        )

        val projections = retrospectiveRepository.findStatisticsByStudentId("student-1")

        assertThat(projections.map { it.problemId })
            .containsExactlyInAnyOrder("1000", "1001")

        val populated = projections.single { it.problemId == "1000" }
        assertThat(populated.createdAt).isEqualTo(createdAt)
        assertThat(populated.solutionResult).isEqualTo(ProblemResult.SUCCESS)
        assertThat(populated.solvedCategory).isEqualTo("DP, Graph")

        val nullable = projections.single { it.problemId == "1001" }
        assertThat(nullable.createdAt).isEqualTo(createdAt.plusDays(1))
        assertThat(nullable.solutionResult).isNull()
        assertThat(nullable.solvedCategory).isNull()
    }

    @Test
    @DisplayName("날짜 범위는 시작을 포함하고 끝을 제외하며 학생별로 제한한다")
    fun appliesStartInclusiveEndExclusiveRangeForStudent() {
        val startInclusive = LocalDateTime.of(2025, 1, 1, 0, 0)
        val endExclusive = LocalDateTime.of(2026, 1, 1, 0, 0)
        retrospectiveRepository.saveAll(
            listOf(
                retrospective("student-1", "before-start", startInclusive.minusSeconds(1)),
                retrospective("student-1", "at-start", startInclusive),
                retrospective("student-1", "inside", startInclusive.plusMonths(6)),
                retrospective("student-1", "before-end", endExclusive.minusSeconds(1)),
                retrospective("student-1", "at-end", endExclusive),
                retrospective("student-2", "other-student", startInclusive.plusDays(1))
            )
        )

        val projections = retrospectiveRepository.findHeatmapByStudentIdAndCreatedAtRange(
            studentId = "student-1",
            startInclusive = startInclusive,
            endExclusive = endExclusive
        )

        assertThat(projections.map { it.problemId })
            .containsExactlyInAnyOrder("at-start", "inside", "before-end")
        assertThat(projections.map { it.createdAt })
            .containsExactlyInAnyOrder(
                startInclusive,
                startInclusive.plusMonths(6),
                endExclusive.minusSeconds(1)
            )
    }

    private fun retrospective(
        studentId: String,
        problemId: String,
        createdAt: LocalDateTime,
        result: ProblemResult? = null,
        solvedCategory: String? = null
    ): Retrospective {
        return Retrospective(
            studentId = studentId,
            problemId = problemId,
            content = "projection 통합 테스트를 위한 충분히 긴 회고 내용입니다.",
            createdAt = createdAt,
            solutionResult = result,
            solvedCategory = solvedCategory
        )
    }
}
