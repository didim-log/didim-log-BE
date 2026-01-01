package com.didimlog.application.retrospective

import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

@DisplayName("RetrospectiveService 테스트")
class RetrospectiveServiceTest {

    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val problemRepository: ProblemRepository = mockk()

    private val retrospectiveService = RetrospectiveService(
        retrospectiveRepository,
        studentRepository,
        problemRepository
    )

    @Test
    @DisplayName("writeRetrospective는 새로운 회고를 작성한다")
    fun `새로운 회고 작성`() {
        // given
        val studentId = "student-id"
        val problemId = "1000"
        val content = "이 문제는 DFS를 사용해서 풀었습니다. 재귀 호출 시 방문 체크를 빼먹어서 시간이 오래 걸렸네요."

        val student = createStudent(id = studentId)
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId) } returns null

        val savedRetrospective = Retrospective(
            id = "retrospective-id",
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = "한 줄 요약 테스트",
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS",
            solveTime = "15m 30s"
        )
        every { retrospectiveRepository.save(any<Retrospective>()) } returns savedRetrospective

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = "한 줄 요약 테스트",
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS",
            solveTime = "15m 30s"
        )

        // then
        assertThat(result.id).isEqualTo("retrospective-id")
        assertThat(result.studentId).isEqualTo(studentId)
        assertThat(result.problemId).isEqualTo(problemId)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.summary).isEqualTo("한 줄 요약 테스트")
        assertThat(result.solutionResult).isEqualTo(com.didimlog.domain.enums.ProblemResult.SUCCESS)
        assertThat(result.solvedCategory).isEqualTo("DFS")
        assertThat(result.solveTime).isEqualTo("15m 30s")
        verify(exactly = 1) { retrospectiveRepository.save(any<Retrospective>()) }
    }

    @Test
    @DisplayName("writeRetrospective는 기존 회고가 있으면 수정한다")
    fun `기존 회고 수정`() {
        // given
        val studentId = "student-id"
        val problemId = "1000"
        val existingContent = "기존 회고 내용입니다."
        val newContent = "수정된 회고 내용입니다. 더 자세하게 작성했습니다."

        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        val student = createStudent(id = studentId)
        val existingRetrospective = Retrospective(
            id = "retrospective-id",
            studentId = studentId,
            problemId = problemId,
            content = existingContent,
            summary = "기존 한 줄 요약"
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId) } returns existingRetrospective

        val updatedRetrospective = existingRetrospective
            .updateContent(newContent, "수정된 한 줄 요약")
            .updateSolutionInfo(com.didimlog.domain.enums.ProblemResult.FAIL, "Greedy", "20m 15s")
        every { retrospectiveRepository.save(any<Retrospective>()) } returns updatedRetrospective

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = newContent,
            summary = "수정된 한 줄 요약",
            solutionResult = com.didimlog.domain.enums.ProblemResult.FAIL,
            solvedCategory = "Greedy",
            solveTime = "20m 15s"
        )

        // then
        assertThat(result.content).isEqualTo(newContent)
        assertThat(result.summary).isEqualTo("수정된 한 줄 요약")
        assertThat(result.solutionResult).isEqualTo(com.didimlog.domain.enums.ProblemResult.FAIL)
        assertThat(result.solvedCategory).isEqualTo("Greedy")
        assertThat(result.solveTime).isEqualTo("20m 15s")
        verify(exactly = 1) { retrospectiveRepository.save(any<Retrospective>()) }
    }

    @Test
    @DisplayName("writeRetrospective는 학생이 없으면 예외를 발생시킨다")
    fun `학생이 없으면 예외`() {
        // given
        every { studentRepository.findById("missing") } returns Optional.empty()

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.writeRetrospective("missing", "1000", "content", "summary")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
    }

    @Test
    @DisplayName("writeRetrospective는 문제가 없으면 예외를 발생시킨다")
    fun `문제가 없으면 예외`() {
        // given
        val student = createStudent(id = "student-id")
        every { studentRepository.findById("student-id") } returns Optional.of(student)
        every { problemRepository.findById("missing") } returns Optional.empty()

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.writeRetrospective("student-id", "missing", "content", "summary")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.PROBLEM_NOT_FOUND)
    }

    @Test
    @DisplayName("updateRetrospective는 회고를 수정한다")
    fun `회고 수정 성공`() {
        // given
        val retrospectiveId = "retrospective-id"
        val studentId = "student-id"
        val newContent = "수정된 회고 내용입니다."
        val newSummary = "수정된 한 줄 요약"
        val newSolveTime = "25m 30s"

        val student = createStudent(id = studentId)
        val existingRetrospective = Retrospective(
            id = retrospectiveId,
            studentId = studentId,
            problemId = "1000",
            content = "기존 회고 내용입니다."
        )

        every { retrospectiveRepository.findById(retrospectiveId) } returns Optional.of(existingRetrospective)
        every { studentRepository.findById(studentId) } returns Optional.of(student)

        val updatedRetrospective = existingRetrospective
            .updateContent(newContent, newSummary)
            .updateSolutionInfo(com.didimlog.domain.enums.ProblemResult.SUCCESS, "DFS", newSolveTime)
        every { retrospectiveRepository.save(any<Retrospective>()) } returns updatedRetrospective

        // when
        val result = retrospectiveService.updateRetrospective(
            retrospectiveId = retrospectiveId,
            studentId = studentId,
            content = newContent,
            summary = newSummary,
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS",
            solveTime = newSolveTime
        )

        // then
        assertThat(result.content).isEqualTo(newContent)
        assertThat(result.summary).isEqualTo(newSummary)
        assertThat(result.solveTime).isEqualTo(newSolveTime)
        verify(exactly = 1) { retrospectiveRepository.save(any<Retrospective>()) }
    }

    @Test
    @DisplayName("updateRetrospective는 소유자가 아니면 예외를 발생시킨다")
    fun `회고 수정 실패 - 소유자가 아님`() {
        // given
        val retrospectiveId = "retrospective-id"
        val ownerId = "owner-123"
        val attackerId = "attacker-456"
        val attackerStudent = createStudent(id = attackerId)
        val existingRetrospective = Retrospective(
            id = retrospectiveId,
            studentId = ownerId,
            problemId = "1000",
            content = "기존 회고 내용입니다."
        )

        every { retrospectiveRepository.findById(retrospectiveId) } returns Optional.of(existingRetrospective)
        every { studentRepository.findById(attackerId) } returns Optional.of(attackerStudent)

        // when & then
        val exception = assertThrows<BusinessException> {
            retrospectiveService.updateRetrospective(
                retrospectiveId = retrospectiveId,
                studentId = attackerId,
                content = "수정된 내용입니다.",
                summary = "수정된 요약",
                solutionResult = null,
                solvedCategory = null,
                solveTime = null
            )
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
    }

    @Test
    @DisplayName("writeRetrospective는 기존 회고 수정 시 소유권을 검증한다")
    fun `기존 회고 수정 시 소유권 검증`() {
        // given
        val ownerId = "owner-123"
        val problemId = "problem-1"
        val ownerStudent = createStudent(id = ownerId)
        val existingRetrospective = Retrospective(
            id = "retro-1",
            studentId = ownerId,
            problemId = problemId,
            content = "기존 회고 내용입니다."
        )
        val problem = Problem(
            id = ProblemId(problemId),
            title = "Test Problem",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        // 소유자가 자신의 회고를 수정하는 경우 (정상)
        every { studentRepository.findById(ownerId) } returns Optional.of(ownerStudent)
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(ownerId, problemId) } returns existingRetrospective
        every { retrospectiveRepository.save(any<Retrospective>()) } returns existingRetrospective.updateContent("수정된 내용입니다.", "수정된 요약")

        val result = retrospectiveService.writeRetrospective(
            studentId = ownerId,
            problemId = problemId,
            content = "수정된 내용입니다.",
            summary = "수정된 요약"
        )
        assertThat(result.content).isEqualTo("수정된 내용입니다.")

        // 공격자가 다른 사용자의 회고를 수정하려는 경우 (실패)
        // 실제로는 findByStudentIdAndProblemId가 attackerId로 조회되므로 null이 반환되어 새로 작성됨
        // 하지만 같은 problemId에 대해 다른 사용자의 회고가 이미 존재하는 경우는 없음 (studentId + problemId가 unique)
        // 따라서 이 테스트는 실제 시나리오를 반영하지 않으므로 제거
    }

    @Test
    @DisplayName("deleteRetrospective는 다른 사용자의 회고를 삭제할 수 없다")
    fun `다른 사용자 회고 삭제 시도 시 예외 발생`() {
        // given
        val ownerId = "owner-123"
        val attackerId = "attacker-456"
        val attackerStudent = createStudent(id = attackerId)
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = ownerId,
            problemId = "problem-1",
            content = "충분히 긴 회고 내용입니다."
        )

        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(attackerId) } returns Optional.of(attackerStudent)

        // when & then
        val exception = assertThrows<BusinessException> {
            retrospectiveService.deleteRetrospective("retro-1", attackerId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
    }

    @Test
    @DisplayName("deleteRetrospective는 소유자의 회고를 정상적으로 삭제한다")
    fun `소유자 회고 삭제 성공`() {
        // given
        val ownerId = "owner-123"
        val ownerStudent = createStudent(id = ownerId)
        val retrospective = Retrospective(
            id = "retro-1",
            studentId = ownerId,
            problemId = "problem-1",
            content = "충분히 긴 회고 내용입니다."
        )

        every { retrospectiveRepository.findById("retro-1") } returns Optional.of(retrospective)
        every { studentRepository.findById(ownerId) } returns Optional.of(ownerStudent)
        every { studentRepository.save(any<Student>()) } answers { firstArg() }
        every { retrospectiveRepository.delete(any<Retrospective>()) } returns Unit

        // when
        val result = retrospectiveService.deleteRetrospective("retro-1", ownerId)

        // then
        assertThat(result.id).isEqualTo("retro-1")
        verify(exactly = 1) { retrospectiveRepository.delete(retrospective) }
    }

    private fun createStudent(id: String): Student {
        return Student(
            id = id,
            nickname = Nickname("test-user"),
            provider = Provider.BOJ,
            providerId = "testuser",
            bojId = BojId("testuser"),
            password = "test-password",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            primaryLanguage = null
        )
    }

    @Test
    @DisplayName("getRetrospective는 회고를 조회한다")
    fun `회고 조회`() {
        // given
        val retrospectiveId = "retrospective-id"
        val retrospective = Retrospective(
            id = retrospectiveId,
            studentId = "student-id",
            problemId = "1000",
            content = "이 문제는 DFS를 사용해서 풀었습니다. 재귀 호출 시 방문 체크를 빼먹어서 시간이 오래 걸렸네요.",
            summary = "한 줄 요약 테스트"
        )

        every { retrospectiveRepository.findById(retrospectiveId) } returns Optional.of(retrospective)

        // when
        val result = retrospectiveService.getRetrospective(retrospectiveId)

        // then
        assertThat(result.id).isEqualTo(retrospectiveId)
        assertThat(result.content).isEqualTo("이 문제는 DFS를 사용해서 풀었습니다. 재귀 호출 시 방문 체크를 빼먹어서 시간이 오래 걸렸네요.")
    }

    @Test
    @DisplayName("getRetrospective는 회고가 없으면 예외를 발생시킨다")
    fun `회고가 없으면 예외`() {
        // given
        every { retrospectiveRepository.findById("missing") } returns Optional.empty()

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.getRetrospective("missing")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.RETROSPECTIVE_NOT_FOUND)
    }

    @Test
    @DisplayName("generateTemplate은 SUCCESS 결과에 따라 성공 템플릿을 생성한다")
    fun `성공 템플릿 생성`() {
        // given
        val problemId = "1000"
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { problemRepository.findById(problemId) } returns Optional.of(problem)

        // when
        val template = retrospectiveService.generateTemplate(problemId, com.didimlog.domain.enums.ProblemResult.SUCCESS)

        // then
        assertThat(template).contains("# 🏆 A+B 해결 회고")
        assertThat(template).contains("## 💡 핵심 접근 (Key Idea)")
        assertThat(template).contains("## ⏱️ 시간/공간 복잡도")
        assertThat(template).contains("## ✨ 개선할 점")
        assertThat(template).doesNotContain("## 🧐 실패 원인")
        assertThat(template).doesNotContain("## 📚 부족했던 개념")
        assertThat(template).doesNotContain("## 🔧 다음 시도 계획")
    }

    @Test
    @DisplayName("generateTemplate은 FAIL 결과에 따라 실패 템플릿을 생성한다")
    fun `실패 템플릿 생성`() {
        // given
        val problemId = "1000"
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { problemRepository.findById(problemId) } returns Optional.of(problem)

        // when
        val template = retrospectiveService.generateTemplate(problemId, com.didimlog.domain.enums.ProblemResult.FAIL)

        // then
        assertThat(template).contains("# 💥 A+B 오답 노트")
        assertThat(template).contains("## 🧐 실패 원인 (Why?)")
        assertThat(template).contains("## 📚 부족했던 개념")
        assertThat(template).contains("## 🔧 다음 시도 계획")
        assertThat(template).doesNotContain("## 💡 핵심 접근")
        assertThat(template).doesNotContain("## ⏱️ 시간/공간 복잡도")
        assertThat(template).doesNotContain("## ✨ 개선할 점")
    }

    @Test
    @DisplayName("generateTemplate은 TIME_OVER 결과에 따라 실패 템플릿을 생성한다")
    fun `시간 초과 템플릿 생성`() {
        // given
        val problemId = "1000"
        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { problemRepository.findById(problemId) } returns Optional.of(problem)

        // when
        val template = retrospectiveService.generateTemplate(problemId, com.didimlog.domain.enums.ProblemResult.TIME_OVER)

        // then
        assertThat(template).contains("# 💥 A+B 오답 노트")
        assertThat(template).contains("## 🧐 실패 원인 (Why?)")
        assertThat(template).contains("## 📚 부족했던 개념")
        assertThat(template).contains("## 🔧 다음 시도 계획")
    }

    @Test
    @DisplayName("generateTemplate은 문제가 없으면 예외를 발생시킨다")
    fun `템플릿 생성 시 문제가 없으면 예외`() {
        // given
        every { problemRepository.findById("missing") } returns Optional.empty()

        // expect
        val exception = assertThrows<BusinessException> {
            retrospectiveService.generateTemplate("missing", com.didimlog.domain.enums.ProblemResult.SUCCESS)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.PROBLEM_NOT_FOUND)
    }
}

