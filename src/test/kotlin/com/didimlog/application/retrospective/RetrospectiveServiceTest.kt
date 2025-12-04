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
            summary = "한 줄 요약 테스트"
        )
        every { retrospectiveRepository.save(any<Retrospective>()) } returns savedRetrospective

        // when
        val result = retrospectiveService.writeRetrospective(studentId, problemId, content, "한 줄 요약 테스트")

        // then
        assertThat(result.id).isEqualTo("retrospective-id")
        assertThat(result.studentId).isEqualTo(studentId)
        assertThat(result.problemId).isEqualTo(problemId)
        assertThat(result.content).isEqualTo(content)
        assertThat(result.summary).isEqualTo("한 줄 요약 테스트")
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

        val updatedRetrospective = existingRetrospective.updateContent(newContent, "수정된 한 줄 요약")
        every { retrospectiveRepository.save(updatedRetrospective) } returns updatedRetrospective

        // when
        val result = retrospectiveService.writeRetrospective(studentId, problemId, newContent, "수정된 한 줄 요약")

        // then
        assertThat(result.content).isEqualTo(newContent)
        assertThat(result.summary).isEqualTo("수정된 한 줄 요약")
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
    @DisplayName("generateTemplate은 문제 정보를 바탕으로 마크다운 템플릿을 생성한다")
    fun `템플릿 생성`() {
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
        val template = retrospectiveService.generateTemplate(problemId)

        // then
        assertThat(template).contains("# A+B")
        assertThat(template).contains("**문제 번호:** $problemId")
        assertThat(template).contains("**난이도:** BRONZE (Level 3)")
        assertThat(template).contains("**카테고리:** Implementation")
        assertThat(template).contains("**문제 링크:** [A+B](https://www.acmicpc.net/problem/$problemId)")
        assertThat(template).contains("## 접근 방법")
        assertThat(template).contains("## 코드")
        assertThat(template).contains("```kotlin")
        assertThat(template).contains("## 회고")
    }

    @Test
    @DisplayName("generateTemplate은 문제가 없으면 예외를 발생시킨다")
    fun `템플릿 생성 시 문제가 없으면 예외`() {
        // given
        every { problemRepository.findById("missing") } returns Optional.empty()

        // expect
        assertThrows<IllegalArgumentException> {
            retrospectiveService.generateTemplate("missing")
        }
    }
}

