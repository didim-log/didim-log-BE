package com.didimlog.global.exception

/**
 * 에러 코드 Enum
 * 각 에러에 대한 HTTP 상태 코드, 에러 코드, 메시지를 정의한다.
 */
enum class ErrorCode(
    val status: Int,
    val code: String,
    val message: String
) {
    // 400 Bad Request
    COMMON_INVALID_INPUT(400, "COMMON_INVALID_INPUT", "입력값이 올바르지 않습니다."),
    COMMON_VALIDATION_FAILED(400, "COMMON_VALIDATION_FAILED", "유효성 검사에 실패했습니다."),
    INVALID_PASSWORD(400, "INVALID_PASSWORD", "비밀번호 정책에 위배됩니다."),
    DUPLICATE_NICKNAME(400, "DUPLICATE_NICKNAME", "이미 사용 중인 닉네임입니다."),
    PASSWORD_MISMATCH(400, "PASSWORD_MISMATCH", "현재 비밀번호가 일치하지 않습니다."),

    // 404 Not Found
    COMMON_RESOURCE_NOT_FOUND(404, "COMMON_RESOURCE_NOT_FOUND", "요청한 자원을 찾을 수 없습니다."),
    STUDENT_NOT_FOUND(404, "STUDENT_NOT_FOUND", "학생을 찾을 수 없습니다."),
    PROBLEM_NOT_FOUND(404, "PROBLEM_NOT_FOUND", "문제를 찾을 수 없습니다."),
    RETROSPECTIVE_NOT_FOUND(404, "RETROSPECTIVE_NOT_FOUND", "회고를 찾을 수 없습니다."),
    QUOTE_NOT_FOUND(404, "QUOTE_NOT_FOUND", "명언을 찾을 수 없습니다."),
    FEEDBACK_NOT_FOUND(404, "FEEDBACK_NOT_FOUND", "피드백을 찾을 수 없습니다."),

    // 409 Conflict
    DUPLICATE_BOJ_ID(409, "DUPLICATE_BOJ_ID", "이미 가입된 백준 아이디입니다."),

    // 500 Internal Server Error
    COMMON_INTERNAL_ERROR(500, "COMMON_INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.")
}
