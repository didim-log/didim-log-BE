package com.didimlog.global.exception

/**
 * 비즈니스 로직 예외
 * 도메인 로직에서 발생하는 예외를 표현한다.
 */
open class BusinessException(
    val errorCode: ErrorCode,
    message: String? = null
) : RuntimeException(message ?: errorCode.message)
