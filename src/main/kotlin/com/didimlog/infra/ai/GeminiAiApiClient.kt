package com.didimlog.infra.ai

import com.didimlog.application.ai.LlmClient
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Gemini LLM Client를 AiApiClient 인터페이스에 맞추는 어댑터
 * 실제 Gemini API를 호출하여 한 줄 리뷰를 생성한다.
 */
class GeminiAiApiClient(
    private val llmClient: LlmClient
) : AiApiClient {

    private val log = LoggerFactory.getLogger(GeminiAiApiClient::class.java)

    override fun requestOneLineReview(prompt: String, timeoutSeconds: Long): AiApiResponse {
        val systemPrompt = """
        당신은 엄격하고 통찰력 있는 알고리즘 코딩 테스트 면접관입니다.
        사용자의 코드를 분석하여, 개발자의 성장에 가장 도움이 될 '단 하나의 핵심 조언'을 한 문장으로 건네세요.
        
        [분석 우선순위 프로토콜]
        다음 순서대로 코드를 검토하고, 가장 먼저 발견된 문제점을 출력하십시오.
        1. 🛑 치명적 오류: 예외 처리 누락, 논리적 버그, 엣지 케이스(Edge Case) 실패 가능성.
        2. 🚀 성능 이슈: 시간 복잡도(Big-O)가 너무 높거나(예: O(N^2)), 불필요한 중복 연산, 메모리 누수.
        3. 🧹 클린 코드: 가독성을 해치는 변수명, 불필요하게 복잡한 로직, 함수 분리 필요성, Kotlin/Java 관용구(Idiom) 미사용.
        4. 👏 칭찬: 위 3가지 문제가 전혀 없다면, 사용된 알고리즘이나 자료구조의 선택을 구체적으로 칭찬(단, 빈말 금지).
    
        [응답 가이드라인]
        - 서론(예: "코드를 분석해보니...", "이 부분은...")을 절대 쓰지 마십시오.
        - "~하는 게 좋습니다" 같은 권유형보다는 "~하여 성능을 개선하세요" 또는 "O(N)으로 최적화가 가능합니다" 처럼 명확한 팩트와 방향을 제시하십시오.
        - 반드시 한국어로, 100자 이내의 한 문장으로 끝내십시오.
    """.trimIndent()

        return try {
            val future = CompletableFuture.supplyAsync {
                val review = llmClient.generateMarkdown(systemPrompt, prompt)
                // 한 줄로 추출: 첫 줄만 사용하거나 줄바꿈 제거
                val oneLineReview = review.lines().firstOrNull()?.trim() ?: review.trim()
                AiApiResponse(
                    rawJson = """{"review":"$oneLineReview"}""",
                    review = oneLineReview
                )
            }

            val result = future.get(timeoutSeconds, TimeUnit.SECONDS)
            log.debug("Gemini AI 한 줄 리뷰 생성 완료: length={}", result.review.length)
            result
        } catch (e: java.util.concurrent.TimeoutException) {
            log.warn("Gemini AI 한 줄 리뷰 생성 타임아웃: timeout={}초", timeoutSeconds)
            throw java.util.concurrent.TimeoutException("AI 리뷰 생성 시간이 초과되었습니다. 최대 대기 시간: ${timeoutSeconds}초")
        } catch (e: Exception) {
            log.error("Gemini AI 한 줄 리뷰 생성 실패", e)
            throw e
        }
    }
}

