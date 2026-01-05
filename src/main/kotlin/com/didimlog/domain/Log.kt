package com.didimlog.domain

import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.valueobject.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "logs")
data class Log(
    @Id
    val id: String? = null,
    val title: LogTitle,
    val content: LogContent,
    val code: LogCode,
    val bojId: BojId? = null,
    val isSuccess: Boolean? = null, // 풀이 성공 여부 (null: 미제출, true: 성공, false: 실패)
    @CreatedDate
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val aiReview: AiReview? = null,
    val aiReviewStatus: AiReviewStatus? = null,
    val aiReviewLockExpiresAt: LocalDateTime? = null,
    val aiReviewDurationMillis: Long? = null,
    val aiFeedbackStatus: AiFeedbackStatus = AiFeedbackStatus.NONE,
    val aiFeedbackReason: String? = null,
    val promptVersion: String = "v1.0"
) {
    fun hasAiReview(): Boolean = aiReview != null

    fun aiReviewTextOrNull(): String? = aiReview?.value

    fun saveAiReview(review: String): Log = copy(aiReview = AiReview(review))

    fun updateFeedback(status: AiFeedbackStatus, reason: String? = null): Log {
        return copy(
            aiFeedbackStatus = status,
            aiFeedbackReason = reason
        )
    }
}


