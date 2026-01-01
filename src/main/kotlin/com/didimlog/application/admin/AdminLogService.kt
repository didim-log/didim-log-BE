package com.didimlog.application.admin

import com.didimlog.domain.Log
import com.didimlog.domain.repository.LogRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자용 로그 서비스
 * 관리자가 AI 리뷰 생성 로그를 조회할 수 있도록 한다.
 */
@Service
class AdminLogService(
    private val logRepository: LogRepository
) {

    /**
     * AI 리뷰 생성 로그를 페이징하여 조회한다.
     *
     * @param bojId BOJ ID 필터 (선택, null이면 전체)
     * @param pageable 페이징 정보
     * @return 로그 페이지
     */
    @Transactional(readOnly = true)
    fun getLogs(bojId: String?, pageable: Pageable): Page<Log> {
        return if (bojId != null) {
            logRepository.findByBojIdValue(bojId, pageable)
        } else {
            logRepository.findAll(pageable)
        }
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

