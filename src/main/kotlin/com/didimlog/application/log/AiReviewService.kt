package com.didimlog.application.log

import com.didimlog.domain.repository.LogRepository
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.global.exception.AiGenerationTimeoutException
import com.didimlog.global.util.CodeLanguageDetector
import com.didimlog.infra.ai.AiApiClient
import java.time.LocalDateTime
import java.util.concurrent.TimeoutException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AiReviewService(
    private val logRepository: LogRepository,
    private val aiApiClient: AiApiClient,
    private val logAiReviewLockRepository: LogAiReviewLockRepository
) {

    @Transactional
    fun requestOneLineReview(logId: String): AiReviewResult {
        val log = findLogOrThrow(logId)
        
        val cachedReview = log.aiReviewTextOrNull()
        if (cachedReview != null) {
            return AiReviewResult(review = cachedReview, cached = true)
        }

        val code = log.code.value.trim()
        if (code.length < MIN_CODE_LENGTH) {
            return AiReviewResult(review = CODE_TOO_SHORT_MESSAGE, cached = false)
        }

        val now = LocalDateTime.now()
        val expiresAt = now.plusSeconds(LOCK_TTL_SECONDS)
        
        if (!tryAcquireLock(logId, now, expiresAt)) {
            return handleLockNotAcquired(logId, now)
        }

        return generateAiReview(logId, code)
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

    private fun generateAiReview(logId: String, code: String): AiReviewResult {
        val language = detectCodeLanguage(code)
        val prompt = buildPrompt(language, truncateCode(code))

        val startTime = System.currentTimeMillis()
        val response = requestAiApiWithErrorHandling(logId, prompt, startTime)
        val duration = System.currentTimeMillis() - startTime

        return saveAiReviewResult(logId, response.review, duration)
    }

    private fun requestAiApiWithErrorHandling(
        logId: String,
        prompt: String,
        startTime: Long
    ): com.didimlog.infra.ai.AiApiResponse {
        return try {
            aiApiClient.requestOneLineReview(prompt, timeoutSeconds = AI_TIMEOUT_SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            val duration = System.currentTimeMillis() - startTime
            logAiReviewLockRepository.markFailed(logId)
            throw AiGenerationTimeoutException(duration, cause = e)
        } catch (e: Exception) {
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

    private fun buildPrompt(language: String, code: String): String {
        return buildString {
            appendLine("이 $language 코드를 분석하고 시간 복잡도나 클린 코드에 초점을 맞춘 도움이 되는 한 줄 리뷰를 제공하세요. 반드시 한국어로 응답하세요.")
            appendLine()
            appendLine("코드:")
            appendLine(code)
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


