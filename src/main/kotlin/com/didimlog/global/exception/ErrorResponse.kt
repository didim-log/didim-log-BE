package com.didimlog.global.exception

/**
 * 공통 에러 응답 DTO
 * 모든 예외 발생 시 클라이언트에게 반환되는 통일된 JSON 포맷을 정의한다.
 */
data class ErrorResponse(
    val status: Int,
    val error: String,
    val code: String,
    val message: String,
    val remainingAttempts: Int? = null, // Rate Limiting 남은 횟수 (선택적)
    val unlockTime: String? = null // Rate Limit 해제 시간 (한국시간, ISO 8601 형식, 선택적)
) {
    companion object {
        fun of(errorCode: ErrorCode): ErrorResponse {
            return ErrorResponse(
                status = errorCode.status,
                error = getHttpStatusName(errorCode.status),
                code = errorCode.code,
                message = errorCode.message
            )
        }

        fun of(errorCode: ErrorCode, customMessage: String): ErrorResponse {
            return ErrorResponse(
                status = errorCode.status,
                error = getHttpStatusName(errorCode.status),
                code = errorCode.code,
                message = customMessage
            )
        }

        fun of(errorCode: ErrorCode, customMessage: String, remainingAttempts: Int): ErrorResponse {
            return ErrorResponse(
                status = errorCode.status,
                error = getHttpStatusName(errorCode.status),
                code = errorCode.code,
                message = customMessage,
                remainingAttempts = remainingAttempts
            )
        }

        private fun getHttpStatusName(status: Int): String {
            return HTTP_STATUS_NAMES[status] ?: DEFAULT_HTTP_STATUS_NAME
        }

        private const val DEFAULT_HTTP_STATUS_NAME = "Error"

        private val HTTP_STATUS_NAMES = mapOf(
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            409 to "Conflict",
            429 to "Too Many Requests",
            500 to "Internal Server Error"
        )
    }
}

