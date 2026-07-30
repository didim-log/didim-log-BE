package com.didimlog.infra.ai

import com.didimlog.application.ai.LlmClient
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.util.retry.Retry
import java.time.Duration

class GeminiLlmClient(
    private val properties: AiGeminiProperties,
    webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper,
    private val rateLimiter: GeminiRateLimiter
) : LlmClient {

    private val log = LoggerFactory.getLogger(GeminiLlmClient::class.java)
    private val geminiWebClient: WebClient = createGeminiWebClient(webClientBuilder)

    override fun extractKeywords(systemPrompt: String, userPrompt: String): String {
        val text = requestText(systemPrompt, userPrompt)
        return extractKeywordsFromText(text)
    }

    override fun generateMarkdown(systemPrompt: String, userPrompt: String): String {
        return requestText(systemPrompt, userPrompt)
    }

    private fun requestText(systemPrompt: String, userPrompt: String): String {
        validateConfiguration()

        return try {
            val response = requestRaw(systemPrompt, userPrompt)
            extractText(response)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("Gemini API 호출 중 예외 발생", e)
            throw BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "AI 서비스 호출 중 오류가 발생했습니다."
            )
        }
    }

    private fun requestRaw(systemPrompt: String, userPrompt: String): String {
        val requestBody = buildRequestBody(systemPrompt, userPrompt)
        var responseMono = Mono.defer {
            rateLimiter.checkAndIncrement()
            geminiWebClient.post()
                .uri(buildRequestUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String::class.java)
        }

        if (properties.maxRetries > 0) {
            responseMono = responseMono.retryWhen(buildRetrySpec())
        }

        return responseMono
            .onErrorResume { throwable ->
                Mono.error(mapToDomainException(throwable))
            }
            .block()
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Gemini 응답이 비어있습니다.")
    }

    private fun buildRetrySpec(): Retry {
        val minimumIntervalMillis =
            properties.rateLimit.minIntervalSeconds * 1_000L
        val retryBackoffMillis = maxOf(
            properties.retryBackoffMillis,
            minimumIntervalMillis,
            1L
        )
        return Retry.backoff(
            properties.maxRetries,
            Duration.ofMillis(retryBackoffMillis)
        )
            .maxBackoff(Duration.ofMillis(maxOf(5_000L, retryBackoffMillis)))
            .jitter(0.0)
            .filter { throwable ->
                throwable is WebClientResponseException.TooManyRequests
            }
            .doBeforeRetry { retrySignal ->
                log.warn(
                    "Gemini API 429 에러 발생, 재시도 중: attempt={}/{}, error={}",
                    retrySignal.totalRetries() + 1,
                    properties.maxRetries,
                    retrySignal.failure().message
                )
            }
            .onRetryExhaustedThrow { _, retrySignal ->
                Exceptions.retryExhausted(
                    "재시도 한도 초과: ${retrySignal.totalRetries()}회 재시도 후에도 실패",
                    retrySignal.failure()
                )
            }
    }

    private fun mapToDomainException(throwable: Throwable): Throwable {
        if (throwable is BusinessException) {
            return throwable
        }

        if (Exceptions.isRetryExhausted(throwable)) {
            val cause = throwable.cause
            if (cause is WebClientResponseException.TooManyRequests) {
                log.error("Gemini API 429 에러: 재시도 한도 초과", cause)
                return BusinessException(
                    ErrorCode.AI_SERVICE_BUSY,
                    "서버 사용량이 많아 잠시 후 다시 시도해주세요."
                )
            }

            log.error("재시도 한도 초과: 원인={}", cause?.javaClass?.simpleName, throwable)
            return BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해주세요."
            )
        }

        if (throwable is WebClientResponseException.TooManyRequests) {
            log.error("Gemini API 429 에러: 재시도 전 실패", throwable)
            return BusinessException(
                ErrorCode.AI_SERVICE_BUSY,
                "서버 사용량이 많아 잠시 후 다시 시도해주세요."
            )
        }

        if (throwable is WebClientResponseException) {
            if (isContextTooLarge(throwable)) {
                log.error(
                    "Gemini API 토큰 제한 초과: status={}, message={}",
                    throwable.statusCode,
                    throwable.message,
                    throwable
                )
                return BusinessException(
                    ErrorCode.AI_CONTEXT_TOO_LARGE,
                    "요청한 내용이 너무 깁니다. 코드를 간소화하거나 일부를 제거한 후 다시 시도해주세요."
                )
            }

            log.error(
                "Gemini API 호출 실패: status={}, message={}",
                throwable.statusCode,
                throwable.message,
                throwable
            )
            return BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해주세요."
            )
        }

        log.error("Gemini API 호출 중 예상치 못한 오류 발생", throwable)
        return BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "AI 서비스 호출 중 오류가 발생했습니다."
        )
    }

    private fun isContextTooLarge(exception: WebClientResponseException): Boolean {
        if (exception.statusCode != HttpStatus.BAD_REQUEST) {
            return false
        }

        val responseBody = exception.responseBodyAsString
        if (responseBody.contains("INVALID_ARGUMENT")) {
            return true
        }

        if (responseBody.contains("context_length_exceeded")) {
            return true
        }

        return responseBody.contains("token") && responseBody.contains("limit")
    }

    private fun extractKeywordsFromText(text: String): String {
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains(",")) {
                val parts = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size in 2..4) {
                    return parts.take(3).joinToString(", ")
                }
            }
        }

        return lines.firstOrNull()?.trim()?.take(100) ?: ""
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

    private fun createGeminiWebClient(webClientBuilder: WebClient.Builder): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMillis)
            .responseTimeout(Duration.ofSeconds(properties.responseTimeoutSeconds))
            .doOnConnected { connection ->
                connection.addHandlerLast(ReadTimeoutHandler(properties.readTimeoutSeconds.toInt()))
                connection.addHandlerLast(WriteTimeoutHandler(properties.writeTimeoutSeconds.toInt()))
            }

        return webClientBuilder
            .clone()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
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
