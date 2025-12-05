package com.didimlog.application.retrospective

import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
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

        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { studentRepository.existsById(studentId) } returns true
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId) } returns null

        val savedRetrospective = Retrospective(
            id = "retrospective-id",
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = "한 줄 요약 테스트",
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS"
        )
        every { retrospectiveRepository.save(any<Retrospective>()) } returns savedRetrospective

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = content,
            summary = "한 줄 요약 테스트",
            solutionResult = com.didimlog.domain.enums.ProblemResult.SUCCESS,
            solvedCategory = "DFS"
        )

        // then
        assertThat(result.id).isEqualTo("retrospective-id")
        assertThat(result.studentId).isEqualTo(studentId)
        assertThat(result.problemId).isEqualTo(problemId)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.summary).isEqualTo("한 줄 요약 테스트")
        assertThat(result.solutionResult).isEqualTo(com.didimlog.domain.enums.ProblemResult.SUCCESS)
        assertThat(result.solvedCategory).isEqualTo("DFS")
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

        val existingRetrospective = Retrospective(
            id = "retrospective-id",
            studentId = studentId,
            problemId = problemId,
            content = existingContent,
            summary = "기존 한 줄 요약"
        )

        every { studentRepository.existsById(studentId) } returns true
        every { problemRepository.findById(problemId) } returns Optional.of(problem)
        every { retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId) } returns existingRetrospective

        val updatedRetrospective = existingRetrospective
            .updateContent(newContent, "수정된 한 줄 요약")
            .updateSolutionInfo(com.didimlog.domain.enums.ProblemResult.FAIL, "Greedy")
        every { retrospectiveRepository.save(any<Retrospective>()) } returns updatedRetrospective

        // when
        val result = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = newContent,
            summary = "수정된 한 줄 요약",
            solutionResult = com.didimlog.domain.enums.ProblemResult.FAIL,
            solvedCategory = "Greedy"
        )

        // then
        assertThat(result.content).isEqualTo(newContent)
        assertThat(result.summary).isEqualTo("수정된 한 줄 요약")
        assertThat(result.solutionResult).isEqualTo(com.didimlog.domain.enums.ProblemResult.FAIL)
        assertThat(result.solvedCategory).isEqualTo("Greedy")
        verify(exactly = 1) { retrospectiveRepository.save(any<Retrospective>()) }
    }

    @Test
    @DisplayName("writeRetrospective는 학생이 없으면 예외를 발생시킨다")
    fun `학생이 없으면 예외`() {
        // given
        every { studentRepository.existsById("missing") } returns false

        // expect
        assertThrows<IllegalArgumentException> {
            retrospectiveService.writeRetrospective("missing", "1000", "content", null)
        }
    }

    @Test
    @DisplayName("writeRetrospective는 문제가 없으면 예외를 발생시킨다")
    fun `문제가 없으면 예외`() {
        // given
        every { studentRepository.existsById("student-id") } returns true
        every { problemRepository.findById("missing") } returns Optional.empty()

        // expect
        assertThrows<IllegalArgumentException> {
            retrospectiveService.writeRetrospective("student-id", "missing", "content", null)
        }
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
        assertThrows<IllegalArgumentException> {
            retrospectiveService.getRetrospective("missing")
        }
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
        assertThrows<IllegalArgumentException> {
            retrospectiveService.generateTemplate("missing", com.didimlog.domain.enums.ProblemResult.SUCCESS)
        }
    }
}

