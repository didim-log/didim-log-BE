package com.didimlog.infra.ai

import org.springframework.stereotype.Component

@Component
class DummyAiApiClient : AiApiClient {
    override fun requestOneLineReview(prompt: String): AiApiResponse {
        Thread.sleep(DELAY_MILLIS)
        val review = "한 줄 리뷰: 핵심 로직은 좋지만 함수 분리를 고려해보세요."
        val rawJson = """{"review":"$review"}"""
        return AiApiResponse(rawJson = rawJson, review = review)
    }

    companion object {
        private const val DELAY_MILLIS = 1_000L
    }
}


