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
        objectMapper: ObjectMapper,
        rateLimiter: GeminiRateLimiter
    ): LlmClient {
        return GeminiLlmClient(properties, webClientBuilder, objectMapper, rateLimiter)
    }

    /**
     * Gemini LLM Client를 AiApiClient 인터페이스에 맞추는 어댑터
     * Gemini가 활성화되어 있을 때만 사용
     */
    @Bean
    @Primary
    @Conditional(GeminiEnabledCondition::class)
    fun geminiAiApiClient(llmClient: LlmClient): com.didimlog.infra.ai.AiApiClient {
        return com.didimlog.infra.ai.GeminiAiApiClient(llmClient)
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

    /**
     * Gemini API가 비활성화된 경우 DummyAiApiClient 사용
     */
    @Bean
    @ConditionalOnMissingBean(com.didimlog.infra.ai.AiApiClient::class)
    fun dummyAiApiClient(): com.didimlog.infra.ai.AiApiClient {
        return com.didimlog.infra.ai.DummyAiApiClient()
    }
}

