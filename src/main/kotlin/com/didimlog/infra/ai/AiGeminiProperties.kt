package com.didimlog.infra.ai

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ai.gemini")
data class AiGeminiProperties(
    val apiKey: String = "",
    val url: String = "",
    val connectTimeoutMillis: Int = 3_000,
    val responseTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 10,
    val writeTimeoutSeconds: Long = 10,
    val maxRetries: Long = 1,
    val retryBackoffMillis: Long = 700,
    val rateLimit: GeminiRateLimitProperties = GeminiRateLimitProperties()
)

data class GeminiRateLimitProperties(
    val minIntervalSeconds: Long = 4,
    val maxRpm: Long = 15,
    val maxRpd: Long = 1_500
) {
    init {
        require(minIntervalSeconds >= 0) {
            "Gemini 최소 호출 간격은 0초 이상이어야 합니다."
        }
        require(maxRpm > 0) {
            "Gemini 분당 호출 한도는 1 이상이어야 합니다."
        }
        require(maxRpd > 0) {
            "Gemini 일일 호출 한도는 1 이상이어야 합니다."
        }
    }
}
