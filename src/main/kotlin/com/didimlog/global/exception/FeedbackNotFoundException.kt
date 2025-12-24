package com.didimlog.global.exception

/**
 * 피드백을 찾을 수 없을 때 발생하는 예외
 */
class FeedbackNotFoundException(message: String) : BusinessException(ErrorCode.FEEDBACK_NOT_FOUND, message)
