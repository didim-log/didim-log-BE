package com.didimlog.global.exception

/**
 * 공통 에러 응답 DTO
 * 모든 예외 발생 시 클라이언트에게 반환되는 통일된 JSON 포맷을 정의한다.
 */
data class ErrorResponse(
    val status: Int,
    val error: String,
    val code: String,
    val message: String
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
            500 to "Internal Server Error"
        )
    }
}

