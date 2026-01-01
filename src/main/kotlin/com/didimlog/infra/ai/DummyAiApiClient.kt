package com.didimlog.infra.ai

class DummyAiApiClient : AiApiClient {
    override fun requestOneLineReview(prompt: String, timeoutSeconds: Long): AiApiResponse {
        Thread.sleep(DELAY_MILLIS)
        if (DELAY_MILLIS > timeoutSeconds * 1000) {
            throw java.util.concurrent.TimeoutException("AI 생성 타임아웃: ${timeoutSeconds}초 초과")
        }
        val review = "한 줄 리뷰: 핵심 로직은 좋지만 함수 분리를 고려해보세요."
        val rawJson = """{"review":"$review"}"""
        return AiApiResponse(rawJson = rawJson, review = review)
    }

    companion object {
        private const val DELAY_MILLIS = 1_000L
    }
}


