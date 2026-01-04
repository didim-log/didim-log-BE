package com.didimlog.global.ratelimit

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode

/**
 * Rate Limit 초과 예외
 */
class RateLimitException(
    message: String,
    val retryAfterSeconds: Long? = null
) : BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, message)









