package com.didimlog.domain

import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * 고객의 소리(Feedback)를 표현하는 도메인 객체
 * 사용자가 버그 리포트나 건의사항을 제출하고, 관리자가 처리 상태를 관리한다.
 */
@Document(collection = "feedbacks")
data class Feedback(
    @Id
    val id: String?,
    val writerId: String, // 작성자 Student ID
    val content: String, // 피드백 내용
    val type: FeedbackType, // 버그/건의
    val status: FeedbackStatus, // 접수/완료
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    init {
        require(content.isNotBlank()) { "피드백 내용은 필수입니다." }
        require(content.length >= 10) { "피드백 내용은 10자 이상이어야 합니다." }
    }

    /**
     * 일반 생성자 (기본값 사용)
     */
    constructor(
        writerId: String,
        content: String,
        type: FeedbackType,
        status: FeedbackStatus = FeedbackStatus.PENDING,
        createdAt: LocalDateTime = LocalDateTime.now(),
        updatedAt: LocalDateTime = LocalDateTime.now()
    ) : this(
        id = null,
        writerId = writerId,
        content = content,
        type = type,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    /**
     * Spring Data MongoDB가 DB에서 데이터를 읽어올 때 사용하는 생성자
     * 파라미터 순서를 변경하여 기본 생성자와 시그니처 충돌을 방지한다.
     */
    @PersistenceCreator
    constructor(
        writerId: String,
        content: String,
        type: FeedbackType,
        status: FeedbackStatus?,
        createdAt: LocalDateTime?,
        updatedAt: LocalDateTime?,
        id: String?
    ) : this(
        id = id,
        writerId = writerId,
        content = content,
        type = type,
        status = status ?: FeedbackStatus.PENDING,
        createdAt = createdAt ?: LocalDateTime.now(),
        updatedAt = updatedAt ?: LocalDateTime.now()
    )

    /**
     * 피드백 상태를 변경한다.
     *
     * @param newStatus 새로운 상태
     * @return 상태가 변경된 새로운 Feedback 인스턴스
     */
    fun updateStatus(newStatus: FeedbackStatus): Feedback {
        return copy(
            status = newStatus,
            updatedAt = LocalDateTime.now()
        )
    }
}
