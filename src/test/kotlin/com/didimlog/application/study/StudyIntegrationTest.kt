package com.didimlog.application.study

import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@DisplayName("StudyService 통합 테스트")
class StudyIntegrationTest {

    @Autowired
    private lateinit var studyService: StudyService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    private lateinit var student: Student
    private lateinit var problem: Problem

    @BeforeEach
    fun setUp() {
        student = Student(
            nickname = Nickname("test-user"),
            bojId = BojId("test123"),
            currentTier = Tier.BRONZE
        )
        student = studentRepository.save(student)

        problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = "IMPLEMENTATION",
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )
        problem = problemRepository.save(problem)
    }

    @Test
    @DisplayName("문제 풀이 후 티어는 자동으로 승급되지 않는다 (Solved.ac 동기화 방식)")
    fun `문제 풀이 후 티어 자동 승급 없음`() {
        // given: 최근 10문제 중 8문제 성공 상태
        val successProblems = (1..8).map { index ->
            problemRepository.save(
                Problem(
                    id = ProblemId("success-$index"),
                    title = "Success Problem $index",
                    category = "TEST",
                    difficulty = Tier.BRONZE,
                    level = 3,
                    url = "https://www.acmicpc.net/problem/success-$index"
                )
            )
        }

        successProblems.forEach { p ->
            studyService.submitSolution(
                studentId = student.id!!,
                problemId = p.id.value,
                timeTaken = 100L,
                isSuccess = true
            )
        }

        // when: 추가 문제를 성공적으로 풀어도
        studyService.submitSolution(
            studentId = student.id!!,
            problemId = problem.id.value,
            timeTaken = 120L,
            isSuccess = true
        )

        // then: 티어는 그대로 유지됨 (Solved.ac API를 통한 외부 동기화만 가능)
        val updatedStudent = studentRepository.findById(student.id!!).orElseThrow()
        assertThat(updatedStudent.tier()).isEqualTo(Tier.BRONZE)
    }

    @Test
    @DisplayName("updateTier를 통해 외부에서 티어를 업데이트할 수 있다")
    fun `외부에서 티어 업데이트 가능`() {
        // given
        assertThat(student.tier()).isEqualTo(Tier.BRONZE)

        // when: Solved.ac API에서 가져온 티어 정보로 업데이트
        val updatedStudent = student.updateTier(Tier.GOLD)
        studentRepository.save(updatedStudent)

        // then: 티어가 업데이트됨
        val savedStudent = studentRepository.findById(student.id!!).orElseThrow()
        assertThat(savedStudent.tier()).isEqualTo(Tier.GOLD)
    }

    @Test
    @DisplayName("문제 풀이 결과가 Solutions에 정상적으로 저장된다")
    fun `문제 풀이 결과 저장`() {
        // given
        val problem1 = problemRepository.save(
            Problem(
                id = ProblemId("p1"),
                title = "Problem 1",
                category = "TEST",
                difficulty = Tier.BRONZE,
                level = 3,
                url = "https://www.acmicpc.net/problem/p1"
            )
        )
        val problem2 = problemRepository.save(
            Problem(
                id = ProblemId("p2"),
                title = "Problem 2",
                category = "TEST",
                difficulty = Tier.BRONZE,
                level = 4,
                url = "https://www.acmicpc.net/problem/p2"
            )
        )

        // when
        studyService.submitSolution(
            studentId = student.id!!,
            problemId = problem1.id.value,
            timeTaken = 100L,
            isSuccess = true
        )
        studyService.submitSolution(
            studentId = student.id!!,
            problemId = problem2.id.value,
            timeTaken = 120L,
            isSuccess = false
        )

        // then: 풀이 기록이 저장되고, 티어는 변경되지 않음
        val updatedStudent = studentRepository.findById(student.id!!).orElseThrow()
        val solvedProblemIds = updatedStudent.getSolvedProblemIds()
        assertThat(solvedProblemIds).containsExactlyInAnyOrder(ProblemId("p1"), ProblemId("p2"))
        assertThat(updatedStudent.tier()).isEqualTo(Tier.BRONZE)
    }
}

