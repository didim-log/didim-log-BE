package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * AI 키워드 추출 서비스
 * 비용 절감을 위해 AI에게 키워드 3개만 요청하여 반환한다.
 * 전체 회고를 생성하지 않고, 오직 핵심 키워드만 추출한다.
 */
@Service
class AiKeywordService(
    private val llmClient: LlmClient,
    private val problemService: ProblemService
) {

    private val log = LoggerFactory.getLogger(AiKeywordService::class.java)

    /**
     * 사용자 코드와 문제 정보를 분석하여 학습 키워드 3개를 추출한다.
     * 성공 시: 알고리즘/자료구조/디자인 패턴 키워드
     * 실패 시: 에러 원인 관련 CS 키워드
     *
     * @param problemId 문제 ID
     * @param code 사용자 코드
     * @param isSuccess 풀이 성공 여부
     * @return 쉼표로 구분된 키워드 문자열 (예: "DFS, 백트래킹, 재귀")
     */
    fun extractKeywords(problemId: String, code: String, isSuccess: Boolean): String {
        val problem = problemService.getProblemDetail(problemId.toLong())

        val systemPrompt = createKeywordExtractionSystemPrompt(isSuccess)
        val userPrompt = createKeywordExtractionUserPrompt(
            problemTitle = problem.title,
            code = code,
            isSuccess = isSuccess
        )

        return try {
            val response = llmClient.extractKeywords(systemPrompt, userPrompt)
            log.debug("AI 키워드 추출 성공: problemId=$problemId, keywords=$response")
            response
        } catch (e: Exception) {
            log.warn("AI 키워드 추출 실패: problemId=$problemId, error=${e.message}", e)
            throw e
        }
    }

    /**
     * 키워드 추출을 위한 시스템 프롬프트를 생성한다.
     * 토큰 절약을 위해 최소한의 지시만 포함한다.
     *
     * @param isSuccess 풀이 성공 여부
     * @return 시스템 프롬프트
     */
    private fun createKeywordExtractionSystemPrompt(isSuccess: Boolean): String {
        if (isSuccess) {
            return """
            당신은 알고리즘 코드 분석 전문가입니다.
            제공된 코드를 분석하여, 학습해야 할 핵심 CS 개념이나 알고리즘/자료구조/디자인 패턴 키워드를 3개만 추출하세요.
            응답은 반드시 쉼표로 구분된 키워드만 반환하세요. (예: "DFS, 백트래킹, 재귀")
            설명이나 서술 없이 키워드만 반환하세요.
            """.trimIndent()
        }

        return """
        당신은 알고리즘 코드 분석 전문가입니다.
        제공된 실패한 코드를 분석하여, 에러 원인과 관련된 핵심 CS 개념이나 학습 키워드를 3개만 추출하세요.
        응답은 반드시 쉼표로 구분된 키워드만 반환하세요. (예: "시간 복잡도, 배열 인덱싱, 경계 조건")
        설명이나 서술 없이 키워드만 반환하세요.
        """.trimIndent()
    }

    /**
     * 키워드 추출을 위한 사용자 프롬프트를 생성한다.
     * 문제 제목과 코드만 포함하여 토큰을 최소화한다.
     *
     * @param problemTitle 문제 제목
     * @param code 사용자 코드
     * @param isSuccess 풀이 성공 여부
     * @return 사용자 프롬프트
     */
    private fun createKeywordExtractionUserPrompt(
        problemTitle: String,
        code: String,
        isSuccess: Boolean
    ): String {
        val resultText = if (isSuccess) "성공" else "실패"
        return """
        문제: $problemTitle
        풀이 결과: $resultText
        
        코드:
        $code
        
        위 코드를 분석하여 학습 키워드 3개를 쉼표로 구분하여 반환하세요.
        """.trimIndent()
    }
}
