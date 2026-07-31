package com.didimlog.global.exception

/**
 * 에러 코드 Enum
 * 각 에러에 대한 HTTP 상태 코드, 에러 코드, 메시지를 정의한다.
 */
enum class ErrorCode(
    val status: Int,
    val code: String,
    val message: String,
    val retryable: Boolean = false
) {
    // 400 Bad Request
    COMMON_INVALID_INPUT(400, "COMMON_INVALID_INPUT", "입력값이 올바르지 않습니다."),
    COMMON_VALIDATION_FAILED(400, "COMMON_VALIDATION_FAILED", "유효성 검사에 실패했습니다."),
    INVALID_RANGE(400, "INVALID_RANGE", "요청한 범위(start/end)가 올바르지 않습니다."),
    INVALID_PASSWORD(400, "INVALID_PASSWORD", "비밀번호 정책에 위배됩니다."),
    DUPLICATE_NICKNAME(400, "DUPLICATE_NICKNAME", "이미 사용 중인 닉네임입니다."),
    PASSWORD_MISMATCH(400, "PASSWORD_MISMATCH", "현재 비밀번호가 일치하지 않습니다."),
    OAUTH_EXCHANGE_CODE_INVALID(400, "OAUTH_EXCHANGE_CODE_INVALID", "OAuth 로그인 요청이 만료되었거나 유효하지 않습니다."),

    // 401 Unauthorized
    UNAUTHORIZED(401, "UNAUTHORIZED", "인증이 필요합니다."),

    // 403 Forbidden
    ACCESS_DENIED(403, "ACCESS_DENIED", "접근 권한이 없습니다."),
    TEMPLATE_CANNOT_DELETE_SYSTEM(403, "TEMPLATE_CANNOT_DELETE_SYSTEM", "시스템 템플릿은 삭제할 수 없습니다."),

    // 503 Service Unavailable
    MAINTENANCE_MODE(503, "MAINTENANCE_MODE", "서비스가 일시적으로 점검 중입니다. 잠시 후 다시 시도해주세요."),
    RATE_LIMIT_SERVICE_UNAVAILABLE(503, "RATE_LIMIT_SERVICE_UNAVAILABLE", "요청 제한 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요.", retryable = true),
    SESSION_STATE_UNAVAILABLE(503, "SESSION_STATE_UNAVAILABLE", "계정 작업 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", retryable = true),
    AI_GENERATION_FAILED(503, "AI_GENERATION_FAILED", "AI 리뷰 생성에 실패했습니다. 잠시 후 다시 시도해주세요."),
    AI_GENERATION_TIMEOUT(503, "AI_GENERATION_TIMEOUT", "AI 리뷰 생성 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.", retryable = true),
    TEMPLATE_RENDER_TIMEOUT(504, "TEMPLATE_RENDER_TIMEOUT", "템플릿 렌더링 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.", retryable = true),

    // 404 Not Found
    COMMON_RESOURCE_NOT_FOUND(404, "COMMON_RESOURCE_NOT_FOUND", "요청한 자원을 찾을 수 없습니다."),
    STUDENT_NOT_FOUND(404, "STUDENT_NOT_FOUND", "학생을 찾을 수 없습니다."),
    PROBLEM_NOT_FOUND(404, "PROBLEM_NOT_FOUND", "문제를 찾을 수 없습니다."),
    RETROSPECTIVE_NOT_FOUND(404, "RETROSPECTIVE_NOT_FOUND", "회고를 찾을 수 없습니다."),
    QUOTE_NOT_FOUND(404, "QUOTE_NOT_FOUND", "명언을 찾을 수 없습니다."),
    FEEDBACK_NOT_FOUND(404, "FEEDBACK_NOT_FOUND", "피드백을 찾을 수 없습니다."),
    TEMPLATE_NOT_FOUND(404, "TEMPLATE_NOT_FOUND", "템플릿을 찾을 수 없습니다."),
    JOB_NOT_FOUND(404, "JOB_NOT_FOUND", "작업을 찾을 수 없습니다."),

    // 409 Conflict
    DUPLICATE_BOJ_ID(409, "DUPLICATE_BOJ_ID", "이미 가입된 백준 아이디입니다."),
    JOB_ALREADY_TERMINAL(409, "JOB_ALREADY_TERMINAL", "이미 종료된 작업입니다."),
    SESSION_STATE_CONFLICT(409, "SESSION_STATE_CONFLICT", "같은 계정의 다른 작업이 처리 중입니다. 잠시 후 다시 시도해주세요.", retryable = true),
    PASSWORD_RESET_CONFLICT(409, "PASSWORD_RESET_CONFLICT", "비밀번호 재설정 상태가 변경되었습니다. 새 재설정 코드를 발급받아 다시 시도해주세요."),
    RESOURCE_STATE_CONFLICT(409, "RESOURCE_STATE_CONFLICT", "요청 중 자원 상태가 변경되었습니다. 다시 시도해주세요.", retryable = true),

    // 400 Bad Request
    AI_CONTEXT_TOO_LARGE(400, "AI_CONTEXT_TOO_LARGE", "요청한 내용이 너무 깁니다. 코드를 간소화하거나 일부를 제거한 후 다시 시도해주세요."),

    // 429 Too Many Requests / 503 Service Unavailable
    AI_SERVICE_BUSY(429, "AI_SERVICE_BUSY", "AI 서비스 사용량이 많아 잠시 후 다시 시도해주세요."),
    TOO_MANY_REQUESTS(429, "TOO_MANY_REQUESTS", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    RATE_LIMIT_EXCEEDED(429, "RATE_LIMIT_EXCEEDED", "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
    AI_SERVICE_DISABLED(503, "AI_SERVICE_DISABLED", "AI 서비스가 일시 중지되었습니다."),
    AI_GLOBAL_LIMIT_EXCEEDED(503, "AI_GLOBAL_LIMIT_EXCEEDED", "현재 서비스 이용량이 많아 AI 기능이 일시 중지되었습니다."),
    AI_USER_LIMIT_EXCEEDED(429, "AI_USER_LIMIT_EXCEEDED", "일일 AI 사용 횟수를 초과했습니다. 내일 다시 이용해주세요."),
    QUEUE_TIMEOUT(503, "QUEUE_TIMEOUT", "작업 대기열에서 시간 초과가 발생했습니다."),
    WORKER_UNAVAILABLE(
        503,
        "WORKER_UNAVAILABLE",
        "작업 처리 워커를 사용할 수 없습니다.",
        retryable = true
    ),

    // 500 Internal Server Error
    COMMON_INTERNAL_ERROR(500, "COMMON_INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.")
}
