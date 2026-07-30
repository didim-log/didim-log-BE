package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiFeedbackStatus

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
