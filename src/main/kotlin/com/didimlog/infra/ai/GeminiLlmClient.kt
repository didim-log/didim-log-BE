package com.didimlog.infra.ai

import com.didimlog.application.ai.LlmClient
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.reactive.function.client.WebClient

class GeminiLlmClient(
    private val properties: AiGeminiProperties,
    private val webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper
) : LlmClient {

    override fun generateMarkdown(systemPrompt: String, userPrompt: String): String {
        validateConfiguration()

        val response = webClientBuilder.build()
            .post()
            .uri(buildRequestUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(buildRequestBody(systemPrompt, userPrompt))
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Gemini 응답이 비어있습니다.")

        return extractText(response)
    }

    private fun validateConfiguration() {
        if (properties.apiKey.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "GEMINI_API_KEY가 설정되어 있지 않습니다.")
        }
        if (properties.url.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Gemini URL 설정이 비어있습니다.")
        }
    }

    private fun buildRequestUrl(): String {
        return UriComponentsBuilder
            .fromHttpUrl(properties.url)
            .queryParam("key", properties.apiKey)
            .build()
            .toUriString()
    }

    private fun buildRequestBody(systemPrompt: String, userPrompt: String): Map<String, Any> {
        return mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemPrompt))
            ),
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(mapOf("text" to userPrompt))
                )
            )
        )
    }

    private fun extractText(rawJson: String): String {
        val root: JsonNode = objectMapper.readTree(rawJson)
        val text = root
            .path("candidates")
            .path(0)
            .path("content")
            .path("parts")
            .path(0)
            .path("text")
            .asText("")

        if (text.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Gemini 응답에서 텍스트를 추출할 수 없습니다.")
        }
        return text
    }
}

