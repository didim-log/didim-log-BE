package com.didimlog.global.ratelimit

data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Int,
    val remainingRequests: Int,
    val retryAfterSeconds: Long?
)
