package com.didimlog.global.exception

class AiGenerationFailedException(
    message: String = ErrorCode.AI_GENERATION_FAILED.message,
    cause: Throwable? = null
) : RuntimeException(message, cause)


