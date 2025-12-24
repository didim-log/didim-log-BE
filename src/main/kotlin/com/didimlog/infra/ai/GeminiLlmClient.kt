package com.didimlog.infra.ai

import com.didimlog.application.ai.LlmClient
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

class GeminiLlmClient(
    private val properties: AiGeminiProperties,
    private val webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper,
    private val rateLimiter: GeminiRateLimiter
) : LlmClient {

    private val log = LoggerFactory.getLogger(GeminiLlmClient::class.java)

    override fun extractKeywords(systemPrompt: String, userPrompt: String): String {
        validateConfiguration()

        // Rate Limiting 체크
        rateLimiter.checkAndIncrement()

        return try {
            val response = webClientBuilder.build()
                .post()
                .uri(buildRequestUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequestBody(systemPrompt, userPrompt))
                .retrieve()
                .bodyToMono(String::class.java)
                .retryWhen(
                    Retry.backoff(3, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(16))
                        .jitter(0.5)
                        .filter { throwable ->
                            throwable is WebClientResponseException.TooManyRequests
                        }
                        .doBeforeRetry { retrySignal ->
                            log.warn(
                                "Gemini API 429 에러 발생, 재시도 중: attempt={}/{}",
                                retrySignal.totalRetries() + 1,
                                3
                            )
                        }
                        .onRetryExhaustedThrow { _, retrySignal ->
                            Exceptions.retryExhausted(
                                "재시도 한도 초과: ${retrySignal.totalRetries()}회 재시도 후에도 실패",
                                retrySignal.failure()
                            )
                        }
                )
                .onErrorResume { throwable ->
                    when {
                        Exceptions.isRetryExhausted(throwable) -> {
                            val cause = throwable.cause
                            if (cause is WebClientResponseException.TooManyRequests) {
                                log.error("Gemini API 429 에러: 재시도 한도 초과", cause)
                                return@onErrorResume Mono.error(throwable)
                            }
                            log.error("재시도 한도 초과: 원인={}", cause?.javaClass?.simpleName, throwable)
                            Mono.error(
                                BusinessException(
                                    ErrorCode.COMMON_INTERNAL_ERROR,
                                    "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해주세요."
                                )
                            )
                        }
                        throwable is WebClientResponseException.TooManyRequests -> {
                            log.error("Gemini API 429 에러: 재시도 전 실패", throwable)
                            Mono.error(
                                BusinessException(
                                    ErrorCode.AI_SERVICE_BUSY,
                                    "서버 사용량이 많아 잠시 후 다시 시도해주세요."
                                )
                            )
                        }
                        throwable is WebClientResponseException -> {
                            if (throwable.statusCode == HttpStatus.BAD_REQUEST) {
                                val responseBody = throwable.responseBodyAsString
                                if (responseBody.contains("INVALID_ARGUMENT") ||
                                    responseBody.contains("context_length_exceeded") ||
                                    responseBody.contains("token") && responseBody.contains("limit")) {
                                    log.error(
                                        "Gemini API 토큰 제한 초과: status={}, message={}",
                                        throwable.statusCode,
                                        throwable.message,
                                        throwable
                                    )
                                    return@onErrorResume Mono.error(
                                        BusinessException(
                                            ErrorCode.AI_CONTEXT_TOO_LARGE,
                                            "요청한 내용이 너무 깁니다. 코드를 간소화하거나 일부를 제거한 후 다시 시도해주세요."
                                        )
                                    )
                                }
                            }
                            log.error(
                                "Gemini API 호출 실패: status={}, message={}",
                                throwable.statusCode,
                                throwable.message,
                                throwable
                            )
                            Mono.error(
                                BusinessException(
                                    ErrorCode.COMMON_INTERNAL_ERROR,
                                    "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해주세요."
                                )
                            )
                        }
                        else -> {
                            log.error("Gemini API 호출 중 예상치 못한 오류 발생", throwable)
                            Mono.error(
                                BusinessException(
                                    ErrorCode.COMMON_INTERNAL_ERROR,
                                    "AI 서비스 호출 중 오류가 발생했습니다."
                                )
                            )
                        }
                    }
                }
                .block()
                ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Gemini 응답이 비어있습니다.")

            val text = extractText(response)
            // 키워드만 추출: 쉼표로 구분된 부분 찾기
            extractKeywordsFromText(text)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("Gemini API 키워드 추출 중 예외 발생", e)
            throw BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "AI 서비스 호출 중 오류가 발생했습니다."
            )
        }
    }

    /**
     * AI 응답 텍스트에서 키워드만 추출한다.
     * 쉼표로 구분된 키워드 패턴을 찾아 반환한다.
     *
     * @param text AI 응답 텍스트
     * @return 추출된 키워드 문자열
     */
    private fun extractKeywordsFromText(text: String): String {
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            // 쉼표로 구분된 키워드 패턴 찾기 (2~4개)
            if (trimmed.contains(",")) {
                val parts = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size in 2..4) {
                    return parts.take(3).joinToString(", ")
                }
            }
        }
        // 패턴을 찾지 못한 경우 첫 줄 반환 (공백 제거)
        return lines.firstOrNull()?.trim()?.take(100) ?: ""
    }

    override fun generateMarkdown(systemPrompt: String, userPrompt: String): String {
        validateConfiguration()

        // Rate Limiting 체크 (RPM, RPD, 최소 간격)
        rateLimiter.checkAndIncrement()

        return try {
            val response = webClientBuilder.build()
                .post()
                .uri(buildRequestUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(buildRequestBody(systemPrompt, userPrompt))
                .retrieve()
                .bodyToMono(String::class.java)
                .retryWhen(
                    Retry.backoff(3, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(16)) // 최대 백오프 16초 (2^3 * 2)
                        .jitter(0.5) // 50% Jitter 적용
                        .filter { throwable ->
                            throwable is WebClientResponseException.TooManyRequests
                        }
                        .doBeforeRetry { retrySignal ->
                            log.warn(
                                "Gemini API 429 에러 발생, 재시도 중: attempt={}/{}, error={}",
                                retrySignal.totalRetries() + 1,
                                3,
                                retrySignal.failure().message
                            )
                        }
                        .onRetryExhaustedThrow { _, retrySignal ->
                            // Exceptions.retryExhausted()를 사용하여 재시도 한도 초과 예외 생성
                            Exceptions.retryExhausted(
                                "재시도 한도 초과: ${retrySignal.totalRetries()}회 재시도 후에도 실패",
                                retrySignal.failure()
                            )
                        }
                )
                .onErrorResume { throwable ->
                    when {
                        Exceptions.isRetryExhausted(throwable) -> {
                            // 재시도 한도 초과 예외인 경우, 원인 예외 확인
                            val cause = throwable.cause
                            if (cause is WebClientResponseException.TooManyRequests) {
                                log.error(
                                    "Gemini API 429 에러: 재시도 한도 초과 (재시도 후 실패)",
                                    cause
                                )
                                // RetryExhaustedException을 그대로 전파하여 GlobalExceptionHandler에서 503으로 처리
                                return@onErrorResume Mono.error(throwable)
                            }
                            // 다른 원인인 경우 기존 로직 유지
                            log.error("재시도 한도 초과: 원인={}", cause?.javaClass?.simpleName, throwable)
                            Mono.error(
                                BusinessException(
                                    ErrorCode.COMMON_INTERNAL_ERROR,
                                    "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해주세요."
                                )
                            )
                        }
                        throwable is WebClientResponseException.TooManyRequests -> {
                            // 재시도 전에 바로 429가 발생한 경우 (필터를 통과하지 못한 경우)
                            log.error("Gemini API 429 에러: 재시도 전 실패", throwable)
                            Mono.error(
                                BusinessException(
                                    ErrorCode.AI_SERVICE_BUSY,
                                    "서버 사용량이 많아 잠시 후 다시 시도해주세요."
                                )
                            )
                        }
                        throwable is WebClientResponseException -> {
                            // 400 Bad Request: 토큰 제한 초과 (1M 토큰) 또는 잘못된 요청
                            if (throwable.statusCode == HttpStatus.BAD_REQUEST) {
                                val responseBody = throwable.responseBodyAsString
                                if (responseBody.contains("INVALID_ARGUMENT") || 
                                    responseBody.contains("context_length_exceeded") ||
                                    responseBody.contains("token") && responseBody.contains("limit")) {
                                    log.error(
                                        "Gemini API 토큰 제한 초과: status={}, message={}",
                                        throwable.statusCode,
                                        throwable.message,
                                        throwable
                                    )
                                    return@onErrorResume Mono.error(
                                        BusinessException(
                                            ErrorCode.AI_CONTEXT_TOO_LARGE,
                                            "요청한 내용이 너무 깁니다. 코드를 간소화하거나 일부를 제거한 후 다시 시도해주세요."
                                        )
                                    )
                                }
                            }
                            
                            log.error(
                                "Gemini API 호출 실패: status={}, message={}",
                                throwable.statusCode,
                                throwable.message,
                                throwable
                            )
                            Mono.error(
                                BusinessException(
                                    ErrorCode.COMMON_INTERNAL_ERROR,
                                    "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해주세요."
                                )
                            )
                        }
                        else -> {
                            log.error("Gemini API 호출 중 예상치 못한 오류 발생", throwable)
                            Mono.error(
                                BusinessException(
                                    ErrorCode.COMMON_INTERNAL_ERROR,
                                    "AI 서비스 호출 중 오류가 발생했습니다."
                                )
                            )
                        }
                    }
                }
                .block()
                ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Gemini 응답이 비어있습니다.")

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

    /**
     * Gemini 1.5 Flash 요청 본문을 생성한다.
     * 
     * **Context Caching 고려사항:**
     * - Gemini 1.5 Flash는 1M Context Window를 지원하므로 청킹 로직 불필요
     * - 시스템 프롬프트는 모든 요청에서 동일하므로 Context Caching 적용 가능
     * - 현재는 기본 구조로 구현하고, 향후 CachedContentService를 통해
     *   시스템 프롬프트를 cachedContent 리소스로 생성하여 재사용할 수 있도록 확장 가능
     * 
     * **비용 최적화:**
     * - 시스템 프롬프트: ~500-1000 토큰 (반복 전송)
     * - 사용자 코드: ~100-5000 토큰 (동적)
     * - Context Caching 적용 시 시스템 프롬프트 전송 비용 거의 제로
     *
     * @param systemPrompt 시스템 프롬프트 (향후 Context Caching 대상)
     * @param userPrompt 사용자 프롬프트 (동적 컨텍스트)
     * @return Gemini API 요청 본문
     */
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

