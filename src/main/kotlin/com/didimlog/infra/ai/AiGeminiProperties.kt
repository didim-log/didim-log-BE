package com.didimlog.infra.ai

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ai.gemini")
data class AiGeminiProperties(
    val apiKey: String = "",
    val url: String = ""
)

