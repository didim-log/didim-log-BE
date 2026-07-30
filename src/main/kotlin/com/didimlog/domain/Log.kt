package com.didimlog.domain

import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "logs")
@CompoundIndexes(
    CompoundIndex(name = "idx_log_student_created", def = "{'studentId': 1, 'createdAt': -1}"),
    CompoundIndex(name = "idx_log_boj_snapshot_created", def = "{'bojId': 1, 'createdAt': -1}"),
    CompoundIndex(name = "idx_log_aireview_created", def = "{'aiReviewStatus': 1, 'createdAt': -1}")
)
data class Log(
    @Id
    val id: String? = null,
    val title: LogTitle,
    val content: LogContent,
    val code: LogCode,
    val studentId: String? = null,
    val bojId: BojId? = null,
    val isSuccess: Boolean? = null, // 풀이 성공 여부 (null: 미제출, true: 성공, false: 실패)
    @CreatedDate
    @Indexed
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

}
