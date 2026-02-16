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
    val retryBackoffMillis: Long = 700
)
