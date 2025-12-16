package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiAnalysisService 테스트")
class AiAnalysisServiceTest {

    private val llmClient: LlmClient = mockk()
    private val promptFactory = AiPromptFactory()
    private val problemService: ProblemService = mockk()
    private val service = AiAnalysisService(llmClient, promptFactory, problemService)

    @Test
    @DisplayName("sectionType에 따라 시스템 프롬프트를 선택해 LLM에 전달한다")
    fun `select prompt by sectionType`() {
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            descriptionHtml = "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem
        every { llmClient.generateMarkdown(any(), any()) } returns "result"

        val result = service.analyze(
            code = "print(1)",
            problemId = "1000",
            sectionType = AiSectionType.REFACTORING,
            isSuccess = true
        )

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) {
            llmClient.generateMarkdown(
                match {
                    it.contains("리팩토링 제안") &&
                        it.contains("RETROSPECTIVE_STANDARDS") &&
                        it.contains("3. **리팩토링 제안 (Refactoring)**")
                },
                match {
                    it.contains("문제 번호: 1000") &&
                        it.contains("문제 제목: A+B") &&
                        it.contains("print(1)") &&
                        it.contains("풀이 결과: true")
                }
            )
        }
    }
}

