package com.didimlog.log.application

import com.didimlog.log.domain.AiFeedbackStatus
import com.didimlog.log.domain.Log

/**
 * 로그의 AI 피드백 필드만 조건부로 갱신한다.
 */
interface LogFeedbackRepository {

    fun updateFeedback(
        logId: String,
        studentId: String,
        status: AiFeedbackStatus,
        reason: String?
    ): Log?
}
