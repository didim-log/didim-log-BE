package com.didimlog.domain

import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.domain.enums.AiReviewStatus
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
    @CreatedDate
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val aiReview: AiReview? = null,
    val aiReviewStatus: AiReviewStatus? = null,
    val aiReviewLockExpiresAt: LocalDateTime? = null
) {
    fun hasAiReview(): Boolean = aiReview != null

    fun aiReviewTextOrNull(): String? = aiReview?.value

    fun saveAiReview(review: String): Log = copy(aiReview = AiReview(review))
}


