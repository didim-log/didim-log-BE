package com.didimlog.application.ai

/**
 * 외부 LLM 호출 인터페이스 (Gemini/GPT 등)
 * - 구현체는 infra 레이어에서 제공한다.
 */
interface LlmClient {

    fun generateMarkdown(systemPrompt: String, userPrompt: String): String
}

