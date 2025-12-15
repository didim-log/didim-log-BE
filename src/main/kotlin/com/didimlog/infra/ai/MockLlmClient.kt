package com.didimlog.infra.ai

import com.didimlog.application.ai.LlmClient

/**
 * 외부 LLM 연동 전, 동작 검증을 위한 Mock 구현체
 */
class MockLlmClient : LlmClient {

    override fun generateMarkdown(systemPrompt: String, userPrompt: String): String {
        return """
        ## (Mock) AI 분석 결과

        - 이 응답은 현재 Mock 입니다.
        - 시스템 프롬프트와 사용자 프롬프트를 기반으로 실제 LLM 연동 시 결과를 생성합니다.
        """.trimIndent()
    }
}

