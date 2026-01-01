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
        val systemPrompt = "당신은 코드 리뷰어입니다. 시간 복잡도, 클린 코드 원칙, 또는 개선 사항에 초점을 맞춰 간결하고 도움이 되는 한 줄 리뷰를 제공하세요. 리뷰 텍스트만 응답하고 추가 설명은 하지 마세요. 반드시 한국어로 응답하세요."

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

