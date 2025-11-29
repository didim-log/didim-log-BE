package com.didimlog.application.study

import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("StudyService 테스트")
class StudyServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val problemRepository: ProblemRepository = mockk()

    private val studyService = StudyService(studentRepository, problemRepository)

    @Test
    @DisplayName("submitSolution은 Student와 Problem을 조회하고 solveProblem과 save를 호출한다")
    fun `submitSolution 정상 흐름`() {
        // given
        val studentId = "student-id"
        val problemId = "1000"

        val student = mockk<Student>(relaxed = true)
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = "IMPLEMENTATION",
            difficulty = Tier.BRONZE,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findById(ProblemId(problemId)) } returns Optional.of(problem)
        every { studentRepository.save(student) } returns student

        // when
        studyService.submitSolution(
            studentId = studentId,
            problemId = problemId,
            timeTaken = 120L,
            isSuccess = true
        )

        // then
        verify(exactly = 1) {
            student.solveProblem(
                problem = problem,
                timeTakenSeconds = any(),
                isSuccess = true
            )
        }
        verify(exactly = 1) { studentRepository.save(student) }
    }

    @Test
    @DisplayName("학생이 존재하지 않으면 submitSolution은 예외를 발생시킨다")
    fun `학생이 없으면 예외`() {
        // given
        every { studentRepository.findById("missing") } returns Optional.empty()

        // expect
        assertThrows<IllegalArgumentException> {
            studyService.submitSolution(
                studentId = "missing",
                problemId = "1000",
                timeTaken = 100L,
                isSuccess = true
            )
        }
    }

    @Test
    @DisplayName("문제가 존재하지 않으면 submitSolution은 예외를 발생시킨다")
    fun `문제가 없으면 예외`() {
        // given
        val student = Student(
            nickname = Nickname("tester"),
            bojId = BojId("tester123"),
            currentTier = Tier.BRONZE
        )
        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { problemRepository.findById(ProblemId("missing-problem")) } returns Optional.empty()

        // expect
        assertThrows<IllegalArgumentException> {
            studyService.submitSolution(
                studentId = "student-id",
                problemId = "missing-problem",
                timeTaken = 100L,
                isSuccess = true
            )
        }
    }
}


