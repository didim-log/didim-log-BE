package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.application.ai.LlmClient
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("AiAnalysisService 테스트")
class AiAnalysisServiceTest {

    private val llmClient: LlmClient = mockk()
    private val templateLoader = com.didimlog.infra.ai.PromptTemplateLoader()
    private val promptFactory = AiPromptFactory(templateLoader)
    private val problemService: ProblemService = mockk()
    private val aiProperties = com.didimlog.infra.ai.AiProperties(enabled = true)
    private val service = AiAnalysisService(llmClient, promptFactory, problemService, aiProperties)

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
                    it.contains("추천 학습 키워드") &&
                        it.contains("코드 상세 회고") &&
                        it.contains("시니어 개발자 멘토")
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
                    it.contains("추천 학습 키워드") &&
                        it.contains("실패 분석 회고") &&
                        it.contains("트러블슈팅 전문가")
                },
                any()
            )
        }
    }

    @Test
    @DisplayName("429 에러가 지속적으로 발생하면 BusinessException(AI_SERVICE_BUSY)이 발생한다")
    fun `429 지속 발생 시 최종 응답 검증`() {
        // given
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
        // LlmClient가 429 에러로 인한 BusinessException을 던진다고 가정
        every { llmClient.generateMarkdown(any(), any()) } throws BusinessException(
            ErrorCode.AI_SERVICE_BUSY,
            "서버 사용량이 많아 잠시 후 다시 시도해주세요."
        )

        // when & then
        val exception = assertThrows<BusinessException> {
            service.analyze(
                code = "print(1)",
                problemId = "1000",
                isSuccess = true
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.AI_SERVICE_BUSY)
        assertThat(exception.message).contains("서버 사용량이 많아")
    }

    @Test
    @DisplayName("AI가 비활성화된 경우 정적 템플릿을 반환한다 (성공 케이스)")
    fun `정적 템플릿 반환 - 성공 케이스`() {
        // given
        val disabledAiProperties = com.didimlog.infra.ai.AiProperties(enabled = false)
        val serviceWithDisabledAi = AiAnalysisService(llmClient, promptFactory, problemService, disabledAiProperties)
        
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

        // when
        val result = serviceWithDisabledAi.analyze(
            code = "print(1 + 2)",
            problemId = "1000",
            isSuccess = true
        )

        // then
        assertThat(result).contains("[회고]")
        assertThat(result).contains("구현")
        assertThat(result).contains("print(1 + 2)")
        assertThat(result).contains("AI 서비스 점검 중")
        assertThat(result).contains("문제 분석 및 접근")
        assertThat(result).contains("제출한 코드")
        assertThat(result).contains("개선할 점 / 배운 점")
        
        // LLM 클라이언트가 호출되지 않아야 함
        verify(exactly = 0) { llmClient.generateMarkdown(any(), any()) }
    }

    @Test
    @DisplayName("AI가 비활성화된 경우 정적 템플릿을 반환한다 (실패 케이스)")
    fun `정적 템플릿 반환 - 실패 케이스`() {
        // given
        val disabledAiProperties = com.didimlog.infra.ai.AiProperties(enabled = false)
        val serviceWithDisabledAi = AiAnalysisService(llmClient, promptFactory, problemService, disabledAiProperties)
        
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.DP,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            descriptionHtml = "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem

        // when
        val result = serviceWithDisabledAi.analyze(
            code = "print(1 + 2)",
            problemId = "1000",
            isSuccess = false,
            errorMessage = "IndexError: list index out of range"
        )

        // then
        assertThat(result).contains("[오답 노트]")
        assertThat(result).contains("다이나믹 프로그래밍")
        assertThat(result).contains("print(1 + 2)")
        assertThat(result).contains("IndexError: list index out of range")
        assertThat(result).contains("AI 서비스 점검 중")
        assertThat(result).contains("발생한 에러")
        assertThat(result).contains("문제 코드")
        assertThat(result).contains("원인 분석")
        assertThat(result).contains("해결 방안")
        
        // LLM 클라이언트가 호출되지 않아야 함
        verify(exactly = 0) { llmClient.generateMarkdown(any(), any()) }
    }

    @Test
    @DisplayName("AI가 비활성화된 경우 errorMessage가 없으면 기본 메시지를 사용한다")
    fun `정적 템플릿 반환 - errorMessage 없음`() {
        // given
        val disabledAiProperties = com.didimlog.infra.ai.AiProperties(enabled = false)
        val serviceWithDisabledAi = AiAnalysisService(llmClient, promptFactory, problemService, disabledAiProperties)
        
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.STRING,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            descriptionHtml = "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem

        // when
        val result = serviceWithDisabledAi.analyze(
            code = "print(1 + 2)",
            problemId = "1000",
            isSuccess = false,
            errorMessage = null
        )

        // then
        assertThat(result).contains("에러 로그를 확인할 수 없습니다.")
    }
}

