package com.didimlog.application.ai

/**
 * 외부 LLM 호출 인터페이스 (Gemini/GPT 등)
 * - 구현체는 infra 레이어에서 제공한다.
 */
interface LlmClient {

    /**
     * 전체 마크다운 회고를 생성한다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt 사용자 프롬프트
     * @return 생성된 마크다운 문자열
     */
    fun generateMarkdown(systemPrompt: String, userPrompt: String): String

    /**
     * 키워드만 추출한다. (비용 절감용)
     * 토큰 절약을 위해 키워드 3개만 반환한다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt 사용자 프롬프트
     * @return 쉼표로 구분된 키워드 문자열 (예: "DFS, 백트래킹, 재귀")
     */
    fun extractKeywords(systemPrompt: String, userPrompt: String): String {
        // 기본 구현: generateMarkdown을 호출하고 키워드만 추출
        // 구현체에서 오버라이드하여 최적화 가능
        val fullResponse = generateMarkdown(systemPrompt, userPrompt)
        return extractKeywordsFromResponse(fullResponse)
    }

    /**
     * 전체 응답에서 키워드만 추출한다.
     * 기본 구현: 첫 줄이나 쉼표로 구분된 부분을 추출
     *
     * @param response 전체 AI 응답
     * @return 추출된 키워드 문자열
     */
    private fun extractKeywordsFromResponse(response: String): String {
        // 쉼표로 구분된 키워드 패턴 찾기
        val lines = response.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains(",") && trimmed.split(",").size in 2..4) {
                return trimmed.split(",").take(3).joinToString(",") { it.trim() }
            }
        }
        // 패턴을 찾지 못한 경우 첫 줄 반환
        return lines.firstOrNull()?.trim() ?: ""
    }
}

