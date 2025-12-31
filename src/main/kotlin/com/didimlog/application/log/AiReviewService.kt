package com.didimlog.application.log

import com.didimlog.domain.repository.LogRepository
import com.didimlog.global.exception.AiGenerationFailedException
import com.didimlog.infra.ai.AiApiClient
import java.time.LocalDateTime
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
        val log = logRepository.findById(logId)
            .orElseThrow { IllegalArgumentException("로그를 찾을 수 없습니다. logId=$logId") }

        val cached = log.aiReviewTextOrNull()
        if (cached != null) {
            return AiReviewResult(review = cached, cached = true)
        }

        val code = log.code.value.trim()
        if (code.length < MIN_CODE_LENGTH) {
            return AiReviewResult(review = CODE_TOO_SHORT_MESSAGE, cached = false)
        }

        val now = LocalDateTime.now()
        val expiresAt = now.plusSeconds(LOCK_TTL_SECONDS)

        val acquired = logAiReviewLockRepository.tryAcquireLock(logId, now, expiresAt)
        if (!acquired) {
            val after = logRepository.findById(logId).orElse(null)
            val afterCached = after?.aiReviewTextOrNull()
            if (afterCached != null) {
                return AiReviewResult(review = afterCached, cached = true)
            }

            if (logAiReviewLockRepository.isInProgress(logId, now)) {
                return AiReviewResult(review = IN_PROGRESS_MESSAGE, cached = false)
            }
            return AiReviewResult(review = IN_PROGRESS_MESSAGE, cached = false)
        }

        val prompt = buildPrompt(log.title.value, truncateCode(code))

        val response = try {
            aiApiClient.requestOneLineReview(prompt)
        } catch (e: Exception) {
            logAiReviewLockRepository.markFailed(logId)
            throw AiGenerationFailedException(cause = e)
        }

        val completed = logAiReviewLockRepository.markCompleted(logId, response.review)
        if (!completed) {
            val after = logRepository.findById(logId).orElse(null)
            val afterCached = after?.aiReviewTextOrNull()
            if (afterCached != null) {
                return AiReviewResult(review = afterCached, cached = true)
            }
            return AiReviewResult(review = response.review, cached = false)
        }

        return AiReviewResult(review = response.review, cached = false)
    }

    private fun truncateCode(code: String): String = code.take(MAX_CODE_LENGTH)

    private fun buildPrompt(title: String, code: String): String {
        return buildString {
            appendLine("다음 코드를 한 줄로 리뷰해주세요.")
            appendLine()
            appendLine("제목: $title")
            appendLine()
            appendLine("코드:")
            appendLine(code)
        }
    }

    companion object {
        private const val MAX_CODE_LENGTH = 2_000
        private const val MIN_CODE_LENGTH = 10
        private const val CODE_TOO_SHORT_MESSAGE = "Code is too short to analyze"
        private const val IN_PROGRESS_MESSAGE = "AI review is being generated. Please retry shortly."
        private const val LOCK_TTL_SECONDS = 30L
    }
}

data class AiReviewResult(
    val review: String,
    val cached: Boolean
)


