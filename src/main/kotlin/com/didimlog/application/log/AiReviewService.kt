package com.didimlog.application.log

import com.didimlog.application.ai.AiUsageService
import com.didimlog.domain.repository.LogRepository
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.AiGenerationTimeoutException
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.util.CodeLanguageDetector
import com.didimlog.infra.ai.AiApiClient
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.HttpClientErrorException
import java.time.LocalDateTime

@Service
class AiReviewService(
    private val logRepository: LogRepository,
    private val aiApiClient: AiApiClient,
    private val logAiReviewLockRepository: LogAiReviewLockRepository,
    private val aiUsageService: AiUsageService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun requestOneLineReview(logId: String): AiReviewResult {
        val logEntity = findLogOrThrow(logId)
        
        val cachedReview = logEntity.aiReviewTextOrNull()
        if (cachedReview != null) {
            return AiReviewResult(review = cachedReview, cached = true)
        }

        val code = logEntity.code.value.trim()
        if (code.length < MIN_CODE_LENGTH) {
            return AiReviewResult(review = CODE_TOO_SHORT_MESSAGE, cached = false)
        }

        // AI 사용량 체크 (사용자 ID가 있는 경우만)
        val userId = logEntity.bojId?.value
        if (userId != null) {
            log.info("Checking AI availability for user: $userId")
            try {
                aiUsageService.checkAvailability(userId)
            } catch (e: BusinessException) {
                // 사용량 제한 초과 시 예외를 그대로 전파
                log.warn("AI availability check failed for user: $userId, reason: ${e.message}")
                throw e
            }
        }

        val now = LocalDateTime.now()
        val expiresAt = now.plusSeconds(LOCK_TTL_SECONDS)
        
        if (!tryAcquireLock(logId, now, expiresAt)) {
            return handleLockNotAcquired(logId, now)
        }

        // AI API 호출 및 사용량 증가는 generateAiReview 내부에서 처리
        return generateAiReview(logId, code, logEntity.isSuccess, userId)
    }

    private fun findLogOrThrow(logId: String): com.didimlog.domain.Log {
        return logRepository.findById(logId)
            .orElseThrow { IllegalArgumentException("로그를 찾을 수 없습니다. logId=$logId") }
    }

    private fun tryAcquireLock(logId: String, now: LocalDateTime, expiresAt: LocalDateTime): Boolean {
        return logAiReviewLockRepository.tryAcquireLock(logId, now, expiresAt)
    }

    private fun handleLockNotAcquired(logId: String, @Suppress("UNUSED_PARAMETER") now: LocalDateTime): AiReviewResult {
        val afterLog = logRepository.findById(logId).orElse(null)
        val afterCached = afterLog?.aiReviewTextOrNull()
        if (afterCached != null) {
            return AiReviewResult(review = afterCached, cached = true)
        }

        return AiReviewResult(review = IN_PROGRESS_MESSAGE, cached = false)
    }

    private fun generateAiReview(logId: String, code: String, isSuccess: Boolean?, userId: String?): AiReviewResult {
        val language = detectCodeLanguage(code)
        val prompt = buildPrompt(language, truncateCode(code), isSuccess)

        val startTime = System.currentTimeMillis()
        val response = try {
            requestAiApiWithErrorHandling(logId, prompt, startTime, userId)
        } catch (e: Exception) {
            // AI 호출 실패 시 사용량 증가하지 않음
            log.error("AI API 호출 실패: logId=$logId, userId=$userId", e)
            throw e
        }
        val duration = System.currentTimeMillis() - startTime

        // AI 호출 성공 후에만 사용량 증가
        if (userId != null) {
            log.info("Incrementing AI usage for user: $userId")
            aiUsageService.incrementUsage(userId)
        }

        return saveAiReviewResult(logId, response.review, duration)
    }

    private fun requestAiApiWithErrorHandling(
        logId: String,
        prompt: String,
        startTime: Long,
        userId: String?
    ): com.didimlog.infra.ai.AiApiResponse {
        return try {
            aiApiClient.requestOneLineReview(prompt, timeoutSeconds = AI_TIMEOUT_SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            val duration = System.currentTimeMillis() - startTime
            logAiReviewLockRepository.markFailed(logId)
            throw AiGenerationTimeoutException(duration, cause = e)
        } catch (e: HttpClientErrorException) {
            // Circuit Breaker: 429 (Too Many Requests) 또는 QuotaExceeded 시 긴급 중지
            if (e.statusCode == HttpStatus.TOO_MANY_REQUESTS || 
                e.message?.contains("QuotaExceeded", ignoreCase = true) == true ||
                e.message?.contains("429", ignoreCase = true) == true) {
                log.error("AI API Quota 초과 감지. 긴급 중지 실행. userId=$userId", e)
                aiUsageService.emergencyStop()
            }
            logAiReviewLockRepository.markFailed(logId)
            throw AiGenerationFailedException(
                message = "AI 리뷰 생성 실패 (소요 시간: ${System.currentTimeMillis() - startTime}ms)",
                cause = e
            )
        } catch (e: Exception) {
            // 기타 예외에서도 Quota 관련 메시지 확인
            if (e.message?.contains("QuotaExceeded", ignoreCase = true) == true ||
                e.message?.contains("429", ignoreCase = true) == true) {
                log.error("AI API Quota 초과 감지. 긴급 중지 실행. userId=$userId", e)
                aiUsageService.emergencyStop()
            }
            logAiReviewLockRepository.markFailed(logId)
            throw AiGenerationFailedException(
                message = "AI 리뷰 생성 실패 (소요 시간: ${System.currentTimeMillis() - startTime}ms)",
                cause = e
            )
        }
    }

    private fun saveAiReviewResult(logId: String, review: String, duration: Long): AiReviewResult {
        val completed = logAiReviewLockRepository.markCompleted(logId, review, duration)
        if (!completed) {
            return handleConcurrentSave(logId, review)
        }

        return AiReviewResult(review = review, cached = false)
    }

    private fun handleConcurrentSave(logId: String, generatedReview: String): AiReviewResult {
        val afterLog = logRepository.findById(logId).orElse(null)
        val afterCached = afterLog?.aiReviewTextOrNull()
        if (afterCached != null) {
            return AiReviewResult(review = afterCached, cached = true)
        }

        return AiReviewResult(review = generatedReview, cached = false)
    }

    private fun truncateCode(code: String): String = code.take(MAX_CODE_LENGTH)

    private fun detectCodeLanguage(code: String): String {
        return CodeLanguageDetector.detect(code)
    }

    private fun buildPromptText(resultContext: String, language: String, reviewFocus: String): String {
        if (resultContext.isNotBlank()) {
            return "${resultContext}이 $language 코드를 분석하고 $reviewFocus 반드시 한국어로 응답하세요."
        }
        return "이 $language 코드를 분석하고 $reviewFocus 반드시 한국어로 응답하세요."
    }

    private fun buildPrompt(language: String, code: String, isSuccess: Boolean?): String {
        return buildString {
            val resultContext = when (isSuccess) {
                true -> "이 코드는 성공적으로 실행되었습니다. "
                false -> "이 코드는 실행에 실패했습니다. "
                null -> ""
            }
            
            val reviewFocus = when (isSuccess) {
                true -> "시간 복잡도 개선이나 코드 품질 향상을 위한 제안에 초점을 맞춰주세요."
                false -> "실패 원인 분석이나 버그 수정을 위한 구체적인 피드백을 제공해주세요."
                null -> "시간 복잡도나 클린 코드 원칙에 초점을 맞춰주세요."
            }
            
            val promptText = buildPromptText(resultContext, language, reviewFocus)
            appendLine(promptText)
            appendLine()
            appendLine("코드:")
            appendLine(code)
            appendLine()
            appendLine("중요: 응답에서 사용자 코드를 인용할 때는 반드시 마크다운 코드 블록을 사용하고, 언어 태그를 '${normalizeLanguageTag(language)}'로 지정해야 합니다. (예: ```${normalizeLanguageTag(language)}). 'text' 태그를 사용하거나 자동 감지하지 마세요.")
        }
    }

    private fun normalizeLanguageTag(language: String): String {
        return when (language.uppercase()) {
            "JAVA" -> "java"
            "PYTHON" -> "python"
            "CPP", "C++" -> "cpp"
            "C" -> "c"
            "JAVASCRIPT", "JS" -> "javascript"
            "TYPESCRIPT", "TS" -> "typescript"
            "CSHARP", "C#" -> "csharp"
            "GO" -> "go"
            "RUST" -> "rust"
            "KOTLIN" -> "kotlin"
            "SWIFT" -> "swift"
            "RUBY" -> "ruby"
            "PHP" -> "php"
            "SCALA" -> "scala"
            else -> language.lowercase()
        }
    }

    companion object {
        private const val MAX_CODE_LENGTH = 2_000
        private const val MIN_CODE_LENGTH = 10
        private const val CODE_TOO_SHORT_MESSAGE = "코드가 너무 짧아 분석할 수 없습니다"
        private const val IN_PROGRESS_MESSAGE = "AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요."
        private const val LOCK_TTL_SECONDS = 30L
        private const val AI_TIMEOUT_SECONDS = 30L
    }
}

data class AiReviewResult(
    val review: String,
    val cached: Boolean
)


