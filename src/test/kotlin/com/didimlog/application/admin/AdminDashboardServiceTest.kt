package com.didimlog.application.admin

import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@DisplayName("AdminDashboardService 테스트")
class AdminDashboardServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val adminDashboardService = AdminDashboardService(studentRepository, retrospectiveRepository)

    @Test
    @DisplayName("대시보드 통계 정보를 조회할 수 있다")
    fun `대시보드 통계 조회 성공`() {
        // given
        val students = listOf(
            createStudentWithSolutions(
                id = "student1",
                solvedCount = 5,
                successCount = 3
            ),
            createStudentWithSolutions(
                id = "student2",
                solvedCount = 3,
                successCount = 2
            )
        )
        val retrospectives = listOf(
            createRetrospective(
                id = "retro1",
                createdAt = LocalDateTime.now()
            ),
            createRetrospective(
                id = "retro2",
                createdAt = LocalDateTime.now().minusDays(1)
            )
        )

        every { studentRepository.count() } returns students.size.toLong()
        every { studentRepository.findAll() } returns students
        every { retrospectiveRepository.findAll() } returns retrospectives

        // when
        val result = adminDashboardService.getDashboardStats()

        // then
        assertThat(result.totalUsers).isEqualTo(2L)
        assertThat(result.totalSolvedProblems).isEqualTo(5L) // SUCCESS인 Solution 개수 (3 + 2)
        assertThat(result.todayRetrospectives).isEqualTo(1L) // 오늘 작성된 회고 수
        verify(exactly = 1) { studentRepository.count() }
        verify(exactly = 1) { studentRepository.findAll() }
        verify(exactly = 1) { retrospectiveRepository.findAll() }
    }

    @Test
    @DisplayName("해결된 문제가 없는 경우 총 해결된 문제 수는 0이다")
    fun `해결된 문제 없을 때 총 해결된 문제 수 0`() {
        // given
        val students = listOf(
            createStudentWithSolutions(
                id = "student1",
                solvedCount = 2,
                successCount = 0
            )
        )

        every { studentRepository.count() } returns students.size.toLong()
        every { studentRepository.findAll() } returns students
        every { retrospectiveRepository.findAll() } returns emptyList()

        // when
        val result = adminDashboardService.getDashboardStats()

        // then
        assertThat(result.totalSolvedProblems).isEqualTo(0L)
    }

    private fun createStudentWithSolutions(
        id: String,
        solvedCount: Int,
        successCount: Int
    ): Student {
        val solutions = Solutions()
        repeat(solvedCount) { index ->
            val result = if (index < successCount) ProblemResult.SUCCESS else ProblemResult.FAIL
            solutions.add(
                Solution(
                    problemId = ProblemId("problem-$index"),
                    timeTaken = TimeTakenSeconds(100L),
                    result = result
                )
            )
        }
        return Student(
            id = id,
            nickname = Nickname("user$id"),
            provider = Provider.BOJ,
            providerId = "user$id",
            bojId = BojId("user$id"),
            password = "encoded",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            solutions = solutions
        )
    }

    private fun createRetrospective(
        id: String,
        createdAt: LocalDateTime
    ): Retrospective {
        return Retrospective(
            id = id,
            studentId = "student1",
            problemId = "problem1",
            content = "회고 내용입니다. 최소 10자 이상이어야 합니다.",
            createdAt = createdAt
        )
    }
}


