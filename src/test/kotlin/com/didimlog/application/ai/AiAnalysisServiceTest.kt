package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.application.ai.LlmClient
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
    @DisplayName("isSuccess에 따라 성공/실패 회고용 시스템 프롬프트를 생성해 LLM에 전달한다")
    fun `create system prompt by isSuccess`() {
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
            isSuccess = true
        )

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) {
            llmClient.generateMarkdown(
                match {
                    it.contains("디딤로그 AI") &&
                        it.contains("분석 (성공)") &&
                        it.contains("효율성") &&
                        it.contains("리팩토링 팁")
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

    @Test
    @DisplayName("isSuccess가 false일 때 실패 회고용 프롬프트를 생성한다")
    fun `create failure prompt when isSuccess is false`() {
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
            isSuccess = false
        )

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) {
            llmClient.generateMarkdown(
                match {
                    it.contains("디딤로그 AI") &&
                        it.contains("분석 (실패)") &&
                        it.contains("실패 원인") &&
                        it.contains("학습해야 할 핵심 키워드")
                },
                any()
            )
        }
    }
}

