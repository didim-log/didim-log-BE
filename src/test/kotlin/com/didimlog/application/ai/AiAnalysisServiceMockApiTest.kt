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
import java.util.Optional

@DisplayName("AiAnalysisService Mock API Interaction 테스트")
class AiAnalysisServiceMockApiTest {

    private val llmClient = mockk<LlmClient>(relaxed = true)
    private val templateLoader = com.didimlog.infra.ai.PromptTemplateLoader()
    private val promptFactory = AiPromptFactory(templateLoader)
    private val problemService = mockk<ProblemService>(relaxed = true)

    private val aiAnalysisService = AiAnalysisService(
        llmClient = llmClient,
        promptFactory = promptFactory,
        problemService = problemService
    )

    @Test
    @DisplayName("analyze는 생성된 프롬프트를 LlmClient에 정확히 전달한다")
    fun `프롬프트가 LlmClient에 정확히 전달됨`() {
        // given
        val problemId = "1000"
        val code = "print(1 + 2)"
        val isSuccess = true

        val problem = Problem(
            id = ProblemId(problemId),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId",
            descriptionHtml = "<p>두 정수를 더하세요</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem
        every { llmClient.generateMarkdown(any(), any()) } returns "generated markdown"

        // when
        val result = aiAnalysisService.analyze(code, problemId, isSuccess)

        // then
        assertThat(result).isEqualTo("generated markdown")

        verify(exactly = 1) {
            llmClient.generateMarkdown(
                systemPrompt = match {
                    it.contains("추천 학습 키워드") &&
                        it.contains("코드 상세 회고") &&
                        it.contains("시니어 개발자 멘토")
                },
                userPrompt = match {
                    it.contains("문제 번호: $problemId") &&
                        it.contains("문제 제목: A+B") &&
                        it.contains(code) &&
                        it.contains("풀이 결과: true")
                }
            )
        }
    }

    @Test
    @DisplayName("analyze는 실패 시 실패용 프롬프트를 LlmClient에 전달한다")
    fun `실패용 프롬프트 전달`() {
        // given
        val problemId = "2000"
        val code = "def solution(): pass"
        val isSuccess = false

        val problem = Problem(
            id = ProblemId(problemId),
            title = "Test Problem",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.SILVER,
            level = 5,
            url = "https://www.acmicpc.net/problem/$problemId",
            descriptionHtml = null
        )

        every { problemService.getProblemDetail(2000L) } returns problem
        every { llmClient.generateMarkdown(any(), any()) } returns "failure analysis"

        // when
        val result = aiAnalysisService.analyze(code, problemId, isSuccess)

        // then
        assertThat(result).isEqualTo("failure analysis")

        verify(exactly = 1) {
            llmClient.generateMarkdown(
                systemPrompt = match {
                    it.contains("추천 학습 키워드") &&
                        it.contains("실패 분석 회고") &&
                        it.contains("트러블슈팅 전문가")
                },
                userPrompt = match {
                    it.contains("풀이 결과: false") &&
                        it.contains(code)
                }
            )
        }
    }

    @Test
    @DisplayName("LlmClient는 실제 API를 호출하지 않고 Mock 응답만 반환한다")
    fun `Mock API 호출 검증`() {
        // given
        val problemId = "1000"
        val code = "test code"
        val mockResponse = "This is a mock response without actual API call"

        val problem = Problem(
            id = ProblemId(problemId),
            title = "Test",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 1,
            url = "https://www.acmicpc.net/problem/$problemId"
        )

        every { problemService.getProblemDetail(1000L) } returns problem
        every { llmClient.generateMarkdown(any(), any()) } returns mockResponse

        // when
        val result = aiAnalysisService.analyze(code, problemId, true)

        // then
        assertThat(result).isEqualTo(mockResponse)

        // verify: 실제 API 호출 없이 Mock만 사용됨을 확인
        verify(exactly = 1) { llmClient.generateMarkdown(any(), any()) }
        verify(exactly = 1) { problemService.getProblemDetail(1000L) } // 문제 조회는 1번 호출됨
    }
}



