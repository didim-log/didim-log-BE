package com.didimlog.global.exception

/**
 * 명언을 찾을 수 없을 때 발생하는 예외
 */
class QuoteNotFoundException(message: String) : BusinessException(ErrorCode.QUOTE_NOT_FOUND, message)





