package com.didimlog.global.ratelimit

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode

class RateLimitUnavailableException(
    cause: Throwable
) : BusinessException(ErrorCode.RATE_LIMIT_SERVICE_UNAVAILABLE) {
    init {
        initCause(cause)
    }
}
