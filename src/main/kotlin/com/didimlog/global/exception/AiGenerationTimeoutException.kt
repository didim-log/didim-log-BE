package com.didimlog.global.exception

/**
 * AI 생성 타임아웃 예외
 * AI API 호출이 지정된 시간 내에 완료되지 않았을 때 발생한다.
 */
class AiGenerationTimeoutException(
    val durationMillis: Long,
    cause: Throwable? = null
) : BusinessException(
    ErrorCode.AI_GENERATION_TIMEOUT,
    "AI 리뷰 생성 시간이 초과되었습니다. 소요 시간: ${durationMillis}ms, 최대 대기 시간: 30초"
) {
    init {
        initCause(cause)
    }
}

