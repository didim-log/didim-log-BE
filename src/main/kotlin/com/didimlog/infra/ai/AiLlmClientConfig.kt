package com.didimlog.infra.ai

import com.didimlog.application.ai.LlmClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Conditional
import org.springframework.web.reactive.function.client.WebClient
import com.fasterxml.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(AiGeminiProperties::class)
class AiLlmClientConfig {

    @Bean
    @Primary
    @Conditional(GeminiEnabledCondition::class)
    fun geminiLlmClient(
        properties: AiGeminiProperties,
        webClientBuilder: WebClient.Builder,
        objectMapper: ObjectMapper
    ): LlmClient {
        return GeminiLlmClient(properties, webClientBuilder, objectMapper)
    }

    /**
     * Gemini API 키가 없을 때만 Mock을 사용한다.
     * - 키는 환경변수(GEMINI_API_KEY)로 주입되어야 한다.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(LlmClient::class)
    fun mockLlmClient(): LlmClient {
        return MockLlmClient()
    }
}

