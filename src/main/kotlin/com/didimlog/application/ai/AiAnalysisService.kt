package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.ai.AiProperties
import org.springframework.stereotype.Service

@Service
class AiAnalysisService(
    private val llmClient: LlmClient,
    private val promptFactory: AiPromptFactory,
    private val problemService: ProblemService,
    private val aiProperties: AiProperties
) {

    fun analyze(code: String, problemId: String, isSuccess: Boolean, errorMessage: String? = null): String {
        if (code.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "code는 비어 있을 수 없습니다.")
        }
        if (problemId.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "problemId는 비어 있을 수 없습니다.")
        }

        val problem = problemService.getProblemDetail(problemId.toLong())

        // Feature Flag 체크: AI가 비활성화된 경우 정적 템플릿 반환
        if (!aiProperties.enabled) {
            return generateStaticTemplate(
                category = problem.category.koreanName,
                code = code,
                isSuccess = isSuccess,
                errorMessage = errorMessage
            )
        }

        // AI 활성화된 경우 기존 로직 실행
        val systemPrompt = promptFactory.createSystemPrompt(isSuccess)
        val userPrompt = promptFactory.createUserPrompt(
            problemId = problemId,
            problemTitle = problem.title,
            problemDescription = problem.descriptionHtml,
            code = code,
            isSuccess = isSuccess
        )
        return llmClient.generateMarkdown(systemPrompt, userPrompt)
    }

    /**
     * 정적 템플릿을 생성한다.
     * AI 서비스가 비활성화된 경우 사용된다.
     *
     * @param category 문제 카테고리 (한글명)
     * @param code 사용자 코드
     * @param isSuccess 풀이 성공 여부
     * @param errorMessage 에러 메시지 (실패 시, nullable)
     * @return 생성된 마크다운 문자열
     */
    private fun generateStaticTemplate(
        category: String,
        code: String,
        isSuccess: Boolean,
        errorMessage: String?
    ): String {
        if (isSuccess) {
            return generateSuccessTemplate(category, code)
        }
        return generateFailureTemplate(category, code, errorMessage ?: "에러 로그를 확인할 수 없습니다.")
    }

    /**
     * 성공 회고 정적 템플릿을 생성한다.
     */
    private fun generateSuccessTemplate(category: String, code: String): String {
        return """
            # [회고] $category 문제 해결 🎉

            > ⚠️ 현재 AI 서비스 점검 중으로, 기본 템플릿이 제공됩니다. 직접 회고를 작성해보세요!

            ## 1. 📝 문제 분석 및 접근 (Self Check)
            - (이 문제의 핵심 알고리즘이나 구현 포인트는 무엇이었나요?)

            ## 2. 💻 제출한 코드
            ```java
            $code
            ```

            ## 3. ✨ 개선할 점 / 배운 점
            - (코드를 짜면서 아쉬웠던 점이나 새롭게 알게 된 문법을 기록해봅시다)
            """.trimIndent()
    }

    /**
     * 실패 회고 정적 템플릿을 생성한다.
     */
    private fun generateFailureTemplate(category: String, code: String, errorMessage: String): String {
        return """
            # [오답 노트] $category 문제 디버깅 🐛

            > ⚠️ 현재 AI 서비스 점검 중입니다. 에러 로그를 보고 원인을 분석해보세요.

            ## 1. 🚨 발생한 에러 (Error Log)
            ```text
            $errorMessage
            ```

            ## 2. 💻 문제 코드
            ```java
            $code
            ```

            ## 3. 🔍 원인 분석 (Why?)
            - (왜 이 에러가 발생했을까요? 논리적 오류? 문법 오류?)

            ## 4. 🛠️ 해결 방안 (How?)
            - (어떻게 수정해야 할까요?)
            """.trimIndent()
    }
}

