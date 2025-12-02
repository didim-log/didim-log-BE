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
            return when (status) {
                400 -> "Bad Request"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                else -> "Error"
            }
        }
    }
}

