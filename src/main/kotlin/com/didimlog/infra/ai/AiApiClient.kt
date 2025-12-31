package com.didimlog.infra.ai

interface AiApiClient {
    fun requestOneLineReview(prompt: String): AiApiResponse
}

data class AiApiResponse(
    val rawJson: String,
    val review: String
)


