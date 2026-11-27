package com.didimlog.ai.infra

interface AiApiClient {
    fun requestOneLineReview(prompt: String, timeoutSeconds: Long = 30): AiApiResponse
}

data class AiApiResponse(
    val rawJson: String,
    val review: String
)


