package com.didimlog.application.ai

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service

@Service
class AiAnalysisService(
    private val llmClient: LlmClient,
    private val promptFactory: AiPromptFactory
) {

    fun analyze(code: String, problemId: String, sectionType: AiSectionType): String {
        if (code.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "code는 비어 있을 수 없습니다.")
        }
        if (problemId.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "problemId는 비어 있을 수 없습니다.")
        }

        val systemPrompt = promptFactory.systemPromptFor(sectionType)
        val userPrompt = promptFactory.userPrompt(problemId, code)
        return llmClient.generateMarkdown(systemPrompt, userPrompt)
    }
}

