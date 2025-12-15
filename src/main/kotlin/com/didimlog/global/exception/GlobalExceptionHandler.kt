package com.didimlog.global.exception

import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 전역 예외 처리 핸들러
 * 모든 컨트롤러에서 발생하는 예외를 일관된 형식으로 처리한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 비밀번호 정책 위반 예외 처리
     */
    @ExceptionHandler(InvalidPasswordException::class)
    fun handleInvalidPasswordException(e: InvalidPasswordException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse.of(ErrorCode.INVALID_PASSWORD, e.message ?: ErrorCode.INVALID_PASSWORD.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * 비즈니스 로직 예외 처리
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse.of(e.errorCode, e.message ?: e.errorCode.message)
        return ResponseEntity.status(e.errorCode.status).body(errorResponse)
    }

    /**
     * 유효성 검사 실패 예외 처리 (DTO 검증)
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errorMessage = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        val errorResponse = ErrorResponse.of(
            ErrorCode.COMMON_VALIDATION_FAILED,
            errorMessage
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * 쿼리 파라미터 검증 실패 예외 처리
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolationException(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val errorMessage = e.constraintViolations
            .joinToString(", ") { violation: ConstraintViolation<*> ->
                "${violation.propertyPath}: ${violation.message}"
            }
        val errorResponse = ErrorResponse.of(
            ErrorCode.COMMON_VALIDATION_FAILED,
            errorMessage
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * 인증 실패 (401)
     * - 주로 필터 레벨에서 처리되지만, 컨트롤러/메서드 레벨에서 발생할 수 있어 대비한다.
     */
    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(e: AuthenticationException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse.of(ErrorCode.UNAUTHORIZED, "인증이 필요합니다. JWT 토큰을 확인해주세요.")
        log.warn("AuthenticationException: {}", e.message)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse)
    }

    /**
     * 권한 부족 (403)
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse.of(ErrorCode.ACCESS_DENIED, "접근 권한이 없습니다.")
        log.warn("AccessDeniedException: {}", e.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse)
    }

    /**
     * 요청 바디 JSON 파싱 실패 처리
     * - enum 매핑 실패, 잘못된 JSON 형식 등
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val message = "요청 본문 형식이 올바르지 않습니다."
        val errorResponse = ErrorResponse.of(ErrorCode.COMMON_INVALID_INPUT, message)
        log.warn("HttpMessageNotReadableException: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * Path/Query 파라미터 타입 변환 실패 처리
     * - 예: period=INVALID, sectionType=UNKNOWN
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        val message = "${e.name}: ${e.value} 값이 올바르지 않습니다."
        val errorResponse = ErrorResponse.of(ErrorCode.COMMON_INVALID_INPUT, message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * IllegalArgumentException 처리
     * 도메인에서 발생하는 일반적인 예외를 처리한다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val message = e.message ?: ErrorCode.COMMON_INVALID_INPUT.message
        val errorResponse = ErrorResponse.of(ErrorCode.COMMON_INVALID_INPUT, message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * IllegalStateException 처리
     * - 서버 내부 상태 오류(예: 필수 값 누락/불변식 위반)를 의미한다.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalStateException(e: IllegalStateException): ResponseEntity<ErrorResponse> {
        log.error("IllegalStateException: {}", e.message, e)
        val message = e.message ?: ErrorCode.COMMON_INTERNAL_ERROR.message
        val errorResponse = ErrorResponse.of(ErrorCode.COMMON_INTERNAL_ERROR, message)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }

    /**
     * 명언 관련 예외 처리
     */
    @ExceptionHandler(QuoteNotFoundException::class)
    fun handleQuoteNotFoundException(e: QuoteNotFoundException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse.of(ErrorCode.QUOTE_NOT_FOUND, e.message ?: ErrorCode.QUOTE_NOT_FOUND.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    /**
     * 피드백 관련 예외 처리
     */
    @ExceptionHandler(FeedbackNotFoundException::class)
    fun handleFeedbackNotFoundException(e: FeedbackNotFoundException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse.of(ErrorCode.FEEDBACK_NOT_FOUND, e.message ?: ErrorCode.FEEDBACK_NOT_FOUND.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    /**
     * 기타 예외 처리
     */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        log.error(
            "Unexpected exception occurred: exceptionType=${e.javaClass.simpleName}, message=${e.message}",
            e
        )
        val errorResponse = ErrorResponse.of(ErrorCode.COMMON_INTERNAL_ERROR)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }
}
