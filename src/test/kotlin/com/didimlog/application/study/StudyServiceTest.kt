package com.didimlog.application.study

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
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("StudyService 테스트")
class StudyServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val problemRepository: ProblemRepository = mockk()

    private val studyService = StudyService(studentRepository, problemRepository)

    @Test
    fun `첫 CAS 성공 결과를 반환한다`() {
        val student = createStudent(documentVersion = 2)
        val problem = createProblem("1000")
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { problemRepository.findById(problem.id.value) } returns Optional.of(problem)
        every {
            studentRepository.updateStudyProgressById(
                studentId = STUDENT_ID,
                expectedDocumentVersion = 2,
                solutions = any(),
                consecutiveSolveDays = 1,
                lastSolvedAt = any()
            )
        } answers {
            student.copy(
                solutions = thirdArg(),
                consecutiveSolveDays = 1,
                lastSolvedAt = arg<LocalDate>(4),
                documentVersion = 3
            )
        }

        val result = studyService.submitSolution(
            studentId = STUDENT_ID,
            problemId = problem.id.value,
            timeTaken = 120,
            isSuccess = true
        )

        assertThat(result.documentVersion).isEqualTo(3)
        assertThat(result.getSolvedProblemIds()).containsExactly(problem.id)
        verify(exactly = 1) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 1) { problemRepository.findById(problem.id.value) }
        verify(exactly = 1) { studentRepository.updateStudyProgressById(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    fun `CAS 충돌 후 최신 풀이 기록으로 다시 계산하고 같은 제출 시각을 사용한다`() {
        val firstProblem = createProblem("1000")
        val concurrentProblem = createProblem("1001")
        val firstSnapshot = createStudent(documentVersion = 0)
        val concurrentSolvedAt = LocalDateTime.now().minusMinutes(1)
        val latestSnapshot = firstSnapshot.copy(
            solutions = solutionsOf(
                Solution(
                    problemId = concurrentProblem.id,
                    timeTaken = TimeTakenSeconds(60),
                    result = ProblemResult.SUCCESS,
                    solvedAt = concurrentSolvedAt
                )
            ),
            consecutiveSolveDays = 1,
            lastSolvedAt = concurrentSolvedAt.toLocalDate(),
            documentVersion = 1
        )
        val capturedSolutions = mutableListOf<Solutions>()

        every { studentRepository.findById(STUDENT_ID) } returnsMany listOf(
            Optional.of(firstSnapshot),
            Optional.of(latestSnapshot)
        )
        every { problemRepository.findById(firstProblem.id.value) } returns Optional.of(firstProblem)
        every {
            studentRepository.updateStudyProgressById(
                studentId = STUDENT_ID,
                expectedDocumentVersion = any(),
                solutions = capture(capturedSolutions),
                consecutiveSolveDays = any(),
                lastSolvedAt = any()
            )
        } returnsMany listOf(
            null,
            latestSnapshot.copy(documentVersion = 2)
        )

        studyService.submitSolution(
            studentId = STUDENT_ID,
            problemId = firstProblem.id.value,
            timeTaken = 120,
            isSuccess = true
        )

        assertThat(capturedSolutions).hasSize(2)
        assertThat(capturedSolutions[0].getAll()).hasSize(1)
        assertThat(capturedSolutions[1].getAll().map { it.problemId })
            .containsExactly(concurrentProblem.id, firstProblem.id)
        assertThat(capturedSolutions[0].getAll().single().solvedAt)
            .isEqualTo(capturedSolutions[1].getAll().last().solvedAt)
        verify(exactly = 2) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 2) { studentRepository.updateStudyProgressById(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `CAS 충돌이 계속되면 네 번 시도 후 재시도 가능한 409 예외를 반환한다`() {
        val snapshots = (0L..3L).map { version ->
            Optional.of(createStudent(documentVersion = version))
        }
        val problem = createProblem("1000")
        every { studentRepository.findById(STUDENT_ID) } returnsMany snapshots
        every { problemRepository.findById(problem.id.value) } returns Optional.of(problem)
        every {
            studentRepository.updateStudyProgressById(any(), any(), any(), any(), any())
        } returns null

        val exception = assertThrows<BusinessException> {
            studyService.submitSolution(STUDENT_ID, problem.id.value, 120, true)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(exception.errorCode.status).isEqualTo(409)
        assertThat(exception.errorCode.retryable).isTrue()

        verify(exactly = 4) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 4) { studentRepository.updateStudyProgressById(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `저장소 오류는 재시도하지 않는다`() {
        val student = createStudent(documentVersion = 0)
        val problem = createProblem("1000")
        val databaseFailure = IllegalStateException("mongo unavailable")
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { problemRepository.findById(problem.id.value) } returns Optional.of(problem)
        every {
            studentRepository.updateStudyProgressById(any(), any(), any(), any(), any())
        } throws databaseFailure

        assertThatThrownBy {
            studyService.submitSolution(STUDENT_ID, problem.id.value, 120, true)
        }.isSameAs(databaseFailure)

        verify(exactly = 1) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 1) { studentRepository.updateStudyProgressById(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `학생이 없으면 문제를 조회하지 않고 404 예외를 반환한다`() {
        every { studentRepository.findById(STUDENT_ID) } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            studyService.submitSolution(STUDENT_ID, "1000", 100, true)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)

        verify(exactly = 0) { problemRepository.findById(any()) }
    }

    @Test
    fun `문제가 없으면 404 예외를 반환한다`() {
        val student = createStudent(documentVersion = 0)
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { problemRepository.findById("missing-problem") } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            studyService.submitSolution(STUDENT_ID, "missing-problem", 100, true)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.PROBLEM_NOT_FOUND)

        verify(exactly = 0) { studentRepository.updateStudyProgressById(any(), any(), any(), any(), any()) }
    }

    private fun createStudent(documentVersion: Long): Student {
        return Student(
            id = STUDENT_ID,
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = "tester123",
            bojId = BojId("tester123"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            documentVersion = documentVersion
        )
    }

    private fun createProblem(problemId: String): Problem {
        return Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )
    }

    private fun solutionsOf(vararg solution: Solution): Solutions {
        return Solutions().apply {
            solution.forEach(::add)
        }
    }

    private companion object {
        const val STUDENT_ID = "student-id"
    }
}
