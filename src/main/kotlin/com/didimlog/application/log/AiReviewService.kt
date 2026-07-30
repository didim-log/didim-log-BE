package com.didimlog.application.log

import com.didimlog.application.ai.AiUsageService
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.AiGenerationTimeoutException
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.CodeLanguageDetector
import com.didimlog.infra.ai.AiApiClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
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
    private val aiUsageService: AiUsageService,
    private val aiReviewCodeCacheService: AiReviewCodeCacheService,
    @Qualifier("aiReviewTaskExecutor")
    private val aiReviewTaskExecutor: TaskExecutor
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
        val cachedByCode = aiReviewCodeCacheService.getCachedReview(code, logEntity.isSuccess)
        if (cachedByCode != null) {
            return AiReviewResult(review = cachedByCode, cached = true)
        }

        val userId = logEntity.studentId
        val now = LocalDateTime.now()
        val expiresAt = now.plusSeconds(LOCK_TTL_SECONDS)
        val lock = tryAcquireLock(logId, now, expiresAt)
            ?: return handleLockNotAcquired(logId, now, code, logEntity.isSuccess)

        val reservation = reserveUsageAfterLock(logId, userId, lock)
        return generateAiReview(
            logId,
            code,
            logEntity.isSuccess,
            userId,
            reservation,
            lock
        )
    }

    @Transactional
    fun requestOneLineReviewAsync(logId: String, requesterStudentId: String): AiReviewResult {
        val logEntity = findLogOrThrow(logId)
        val usageUserId = requireOwner(logEntity, requesterStudentId)

        val cachedReview = logEntity.aiReviewTextOrNull()
        if (cachedReview != null) {
            return AiReviewResult(review = cachedReview, cached = true)
        }

        val code = logEntity.code.value.trim()
        if (code.length < MIN_CODE_LENGTH) {
            return AiReviewResult(review = CODE_TOO_SHORT_MESSAGE, cached = false)
        }
        val cachedByCode = aiReviewCodeCacheService.getCachedReview(code, logEntity.isSuccess)
        if (cachedByCode != null) {
            return AiReviewResult(review = cachedByCode, cached = true)
        }

        val now = LocalDateTime.now()
        val expiresAt = now.plusSeconds(LOCK_TTL_SECONDS)
        val lock = tryAcquireLock(logId, now, expiresAt)
            ?: return handleLockNotAcquired(logId, now, code, logEntity.isSuccess)

        val reservation = reserveUsageAfterLock(logId, usageUserId, lock)
        scheduleAiReviewGeneration(
            logId,
            code,
            logEntity.isSuccess,
            usageUserId,
            reservation,
            lock
        )

        return AiReviewResult(review = IN_PROGRESS_MESSAGE, cached = false, inProgress = true)
    }

    private fun requireOwner(
        logEntity: com.didimlog.domain.Log,
        requesterStudentId: String
    ): String {
        if (requesterStudentId.isBlank()) {
            throw BusinessException(ErrorCode.UNAUTHORIZED, "인증이 필요합니다.")
        }

        if (logEntity.studentId != requesterStudentId) {
            throw BusinessException(
                ErrorCode.ACCESS_DENIED,
                "본인이 작성한 로그에 대해서만 AI 리뷰를 요청할 수 있습니다."
            )
        }

        return requesterStudentId
    }

    private fun reserveUsageAfterLock(
        logId: String,
        userId: String?,
        lock: AiReviewLock
    ): AiUsageService.UsageReservation? {
        if (userId == null) {
            return null
        }

        log.info("Reserving AI usage for user: $userId")
        return try {
            aiUsageService.reserveUsage(userId)
        } catch (e: RuntimeException) {
            log.warn("AI usage reservation failed for user: $userId, reason: ${e.message}")
            logAiReviewLockRepository.markFailed(
                logId,
                lock,
                LocalDateTime.now()
            )
            throw e
        }
    }

    private fun scheduleAiReviewGeneration(
        logId: String,
        code: String,
        isSuccess: Boolean?,
        userId: String?,
        reservation: AiUsageService.UsageReservation?,
        lock: AiReviewLock
    ) {
        try {
            aiReviewTaskExecutor.execute {
                if (!renewLockBeforeGeneration(logId, userId, reservation, lock)) {
                    return@execute
                }
                try {
                    generateAiReview(
                        logId,
                        code,
                        isSuccess,
                        userId,
                        reservation,
                        lock
                    )
                } catch (e: Exception) {
                    log.error("비동기 AI 리뷰 생성 실패: logId=$logId, userId=$userId", e)
                }
            }
        } catch (e: Exception) {
            releaseUsage(reservation, logId, userId)
            logAiReviewLockRepository.markFailed(
                logId,
                lock,
                LocalDateTime.now()
            )
            throw AiGenerationFailedException("AI 리뷰 작업 등록에 실패했습니다.", e)
        }
    }

    private fun renewLockBeforeGeneration(
        logId: String,
        userId: String?,
        reservation: AiUsageService.UsageReservation?,
        lock: AiReviewLock
    ): Boolean {
        val now = LocalDateTime.now()
        val renewed = try {
            logAiReviewLockRepository.renewLock(
                logId,
                lock,
                now,
                now.plusSeconds(LOCK_TTL_SECONDS)
            )
        } catch (e: RuntimeException) {
            log.error(
                "비동기 AI 리뷰 잠금 갱신 실패: logId=$logId, userId=$userId",
                e
            )
            false
        }
        if (!renewed) {
            releaseUsage(reservation, logId, userId)
        }
        return renewed
    }

    private fun findLogOrThrow(logId: String): com.didimlog.domain.Log {
        return logRepository.findById(logId)
            .orElseThrow { IllegalArgumentException("로그를 찾을 수 없습니다. logId=$logId") }
    }

    private fun tryAcquireLock(
        logId: String,
        now: LocalDateTime,
        expiresAt: LocalDateTime
    ): AiReviewLock? {
        return logAiReviewLockRepository.tryAcquireLock(logId, now, expiresAt)
    }

    private fun handleLockNotAcquired(
        logId: String,
        @Suppress("UNUSED_PARAMETER") now: LocalDateTime,
        code: String,
        isSuccess: Boolean?
    ): AiReviewResult {
        val afterLog = logRepository.findById(logId).orElse(null)
        val afterCached = afterLog?.aiReviewTextOrNull()
        if (afterCached != null) {
            return AiReviewResult(review = afterCached, cached = true)
        }
        val cachedByCode = aiReviewCodeCacheService.getCachedReview(code, isSuccess)
        if (cachedByCode != null) {
            return AiReviewResult(review = cachedByCode, cached = true)
        }

        return AiReviewResult(review = IN_PROGRESS_MESSAGE, cached = false, inProgress = true)
    }

    private fun generateAiReview(
        logId: String,
        code: String,
        isSuccess: Boolean?,
        userId: String?,
        reservation: AiUsageService.UsageReservation?,
        lock: AiReviewLock
    ): AiReviewResult {
        val startTime = System.currentTimeMillis()
        val response = try {
            val language = detectCodeLanguage(code)
            val prompt = buildPrompt(language, truncateCode(code), isSuccess)
            requestAiApiWithErrorHandling(prompt, startTime, userId)
        } catch (e: Exception) {
            releaseUsage(reservation, logId, userId)
            logAiReviewLockRepository.markFailed(
                logId,
                lock,
                LocalDateTime.now()
            )
            log.error("AI API 호출 실패: logId=$logId, userId=$userId", e)
            throw e
        }
        val duration = System.currentTimeMillis() - startTime

        val completed = logAiReviewLockRepository.markCompleted(
            logId,
            lock,
            LocalDateTime.now(),
            response.review,
            duration
        )
        if (!completed) {
            return handleConcurrentSave(logId, lock)
        }

        aiReviewCodeCacheService.cacheReview(code, isSuccess, response.review)
        return AiReviewResult(review = response.review, cached = false)
    }

    private fun releaseUsage(
        reservation: AiUsageService.UsageReservation?,
        logId: String,
        userId: String?
    ) {
        if (reservation == null) {
            return
        }

        try {
            val released = aiUsageService.releaseUsage(reservation)
            log.debug(
                "AI usage reservation release: logId={}, userId={}, released={}",
                logId,
                userId,
                released
            )
        } catch (e: RuntimeException) {
            log.error("AI 사용량 예약 해제 실패: logId=$logId, userId=$userId", e)
        }
    }

    private fun requestAiApiWithErrorHandling(
        prompt: String,
        startTime: Long,
        userId: String?
    ): com.didimlog.infra.ai.AiApiResponse {
        return try {
            aiApiClient.requestOneLineReview(prompt, timeoutSeconds = AI_TIMEOUT_SECONDS)
        } catch (e: BusinessException) {
            if (e.errorCode in PRESERVED_AI_ERROR_CODES) {
                throw e
            }
            throw AiGenerationFailedException(
                message = "AI 리뷰 생성 실패 (소요 시간: ${System.currentTimeMillis() - startTime}ms)",
                cause = e
            )
        } catch (e: java.util.concurrent.TimeoutException) {
            val duration = System.currentTimeMillis() - startTime
            throw AiGenerationTimeoutException(duration, cause = e)
        } catch (e: HttpClientErrorException) {
            // Circuit Breaker: 429 (Too Many Requests) 또는 QuotaExceeded 시 긴급 중지
            if (e.statusCode == HttpStatus.TOO_MANY_REQUESTS || 
                e.message?.contains("QuotaExceeded", ignoreCase = true) == true ||
                e.message?.contains("429", ignoreCase = true) == true) {
                log.error("AI API Quota 초과 감지. 긴급 중지 실행. userId=$userId", e)
                aiUsageService.emergencyStop()
            }
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
            throw AiGenerationFailedException(
                message = "AI 리뷰 생성 실패 (소요 시간: ${System.currentTimeMillis() - startTime}ms)",
                cause = e
            )
        }
    }

    private fun handleConcurrentSave(
        logId: String,
        lostLock: AiReviewLock
    ): AiReviewResult {
        val now = LocalDateTime.now()
        val currentLog = logRepository.findById(logId).orElse(null)
        val currentReview = currentLog?.aiReviewTextOrNull()
        if (currentReview != null) {
            return AiReviewResult(review = currentReview, cached = true)
        }

        val hasActiveSuccessor =
            currentLog?.aiReviewStatus == AiReviewStatus.IN_PROGRESS &&
                currentLog.aiReviewLockVersion > lostLock.version &&
                currentLog.aiReviewLockExpiresAt?.isAfter(now) == true
        if (hasActiveSuccessor) {
            return AiReviewResult(
                review = IN_PROGRESS_MESSAGE,
                cached = false,
                inProgress = true
            )
        }

        throw AiGenerationFailedException(
            "AI 리뷰 결과를 저장할 수 없습니다. 다시 시도해주세요."
        )
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
        private val PRESERVED_AI_ERROR_CODES = setOf(
            ErrorCode.AI_CONTEXT_TOO_LARGE,
            ErrorCode.AI_SERVICE_BUSY,
            ErrorCode.RATE_LIMIT_SERVICE_UNAVAILABLE
        )

        private const val MAX_CODE_LENGTH = 2_000
        private const val MIN_CODE_LENGTH = 10
        private const val CODE_TOO_SHORT_MESSAGE = "코드가 너무 짧아 분석할 수 없습니다"
        private const val IN_PROGRESS_MESSAGE = "AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요."
        private const val LOCK_TTL_SECONDS = 45L
        private const val AI_TIMEOUT_SECONDS = 12L
    }
}

data class AiReviewResult(
    val review: String,
    val cached: Boolean,
    val inProgress: Boolean = false
)
