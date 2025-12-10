package com.didimlog.application.feedback

import com.didimlog.domain.Feedback
import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.repository.FeedbackRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 피드백 서비스
 * 사용자의 버그 리포트 및 건의사항을 관리한다.
 */
@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository
) {

    /**
     * 피드백을 등록한다.
     *
     * @param writerId 작성자 ID (Student ID)
     * @param content 피드백 내용
     * @param type 피드백 유형 (BUG, SUGGESTION)
     * @return 저장된 피드백
     */
    @Transactional
    fun createFeedback(writerId: String, content: String, type: FeedbackType): Feedback {
        val feedback = Feedback(
            writerId = writerId,
            content = content,
            type = type
        )
        return feedbackRepository.save(feedback)
    }

    /**
     * 피드백 목록을 페이징하여 조회한다.
     *
     * @param pageable 페이징 정보
     * @return 피드백 목록 페이지
     */
    @Transactional(readOnly = true)
    fun getAllFeedbacks(pageable: Pageable): Page<Feedback> {
        return feedbackRepository.findAll(pageable)
    }

    /**
     * 피드백 상태를 변경한다.
     *
     * @param feedbackId 피드백 ID
     * @param newStatus 새로운 상태 (PENDING, COMPLETED)
     * @return 상태가 변경된 피드백
     * @throws BusinessException 피드백을 찾을 수 없는 경우
     */
    @Transactional
    fun updateFeedbackStatus(feedbackId: String, newStatus: FeedbackStatus): Feedback {
        val feedback = feedbackRepository.findById(feedbackId)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "피드백을 찾을 수 없습니다. feedbackId=$feedbackId")
            }
        
        val updatedFeedback = feedback.updateStatus(newStatus)
        return feedbackRepository.save(updatedFeedback)
    }
}

