package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service

@Service
class AiAnalysisService(
    private val llmClient: LlmClient,
    private val promptFactory: AiPromptFactory,
    private val problemService: ProblemService
) {

    fun analyze(code: String, problemId: String, isSuccess: Boolean): String {
        if (code.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "code는 비어 있을 수 없습니다.")
        }
        if (problemId.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "problemId는 비어 있을 수 없습니다.")
        }

        val problem = problemService.getProblemDetail(problemId.toLong())
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
}

