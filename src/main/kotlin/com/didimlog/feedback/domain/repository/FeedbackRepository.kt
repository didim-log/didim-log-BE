package com.didimlog.feedback.domain.repository

import com.didimlog.feedback.domain.Feedback
import com.didimlog.feedback.domain.FeedbackStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository

interface FeedbackRepository : MongoRepository<Feedback, String> {

    /**
     * 상태별로 피드백 목록을 페이징하여 조회한다.
     */
    fun findByStatus(status: FeedbackStatus, pageable: Pageable): Page<Feedback>

    /**
     * 작성자 ID로 피드백 목록을 조회한다.
     */
    fun findByWriterId(writerId: String): List<Feedback>

    fun deleteAllByWriterId(writerId: String)
}

