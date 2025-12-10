package com.didimlog.global.exception

import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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
     * IllegalArgumentException 처리
     * 도메인에서 발생하는 일반적인 예외를 처리한다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val errorCode = when {
            e.message?.contains("학생") == true -> ErrorCode.STUDENT_NOT_FOUND
            e.message?.contains("문제") == true -> ErrorCode.PROBLEM_NOT_FOUND
            e.message?.contains("회고") == true -> ErrorCode.RETROSPECTIVE_NOT_FOUND
            e.message?.contains("명언") == true -> ErrorCode.QUOTE_NOT_FOUND
            e.message?.contains("피드백") == true -> ErrorCode.FEEDBACK_NOT_FOUND
            else -> ErrorCode.COMMON_RESOURCE_NOT_FOUND
        }
        val errorResponse = ErrorResponse.of(errorCode, e.message ?: errorCode.message)
        return ResponseEntity.status(errorCode.status).body(errorResponse)
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
        log.error("Stack trace:", e)
        e.printStackTrace()
        val errorResponse = ErrorResponse.of(ErrorCode.COMMON_INTERNAL_ERROR)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }
}

