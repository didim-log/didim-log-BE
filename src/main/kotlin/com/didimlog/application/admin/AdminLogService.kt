package com.didimlog.application.admin

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.repository.LogRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자용 로그 서비스
 * 관리자가 AI 리뷰 생성 로그를 조회할 수 있도록 한다.
 * 실제 AI 생성이 시도된 로그만 조회한다 (COMPLETED, FAILED 상태).
 */
@Service
class AdminLogService(
    private val logRepository: LogRepository
) {

    /**
     * AI 리뷰 생성 로그를 페이징하여 조회한다.
     * 실제 AI 생성이 시도되고 완료된 로그만 조회한다 (COMPLETED, FAILED 상태).
     * INIT, PENDING, null 상태의 로그는 제외한다.
     *
     * @param bojId BOJ ID 필터 (선택, null이면 전체)
     * @param pageable 페이징 정보
     * @return 로그 페이지
     */
    @Transactional(readOnly = true)
    fun getLogs(bojId: String?, pageable: Pageable): Page<Log> {
        // 실제 AI 생성이 시도된 로그만 조회 (COMPLETED, FAILED)
        val meaningfulStatuses = listOf(AiReviewStatus.COMPLETED, AiReviewStatus.FAILED)
        
        if (bojId != null) {
            return logRepository.findByBojIdValueAndAiReviewStatusInOrderByCreatedAtDesc(
                bojId,
                meaningfulStatuses,
                pageable
            )
        }
        
        return logRepository.findAllByAiReviewStatusInOrderByCreatedAtDesc(meaningfulStatuses, pageable)
    }

    /**
     * 특정 로그를 조회한다.
     *
     * @param logId 로그 ID
     * @return 로그
     */
    @Transactional(readOnly = true)
    fun getLog(logId: String): Log {
        return logRepository.findById(logId)
            .orElseThrow { IllegalArgumentException("로그를 찾을 수 없습니다. logId=$logId") }
    }
}

