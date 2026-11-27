package com.didimlog.global.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@DisplayName("전역 예외 처리기 테스트")
class GlobalExceptionHandlerTest {

    private val exceptionHandler = GlobalExceptionHandler()

    @Test
    @DisplayName("낙관적 잠금 충돌을 재시도 가능한 409 응답으로 변환한다")
    fun `maps optimistic locking conflict to retryable response`() {
        val response = exceptionHandler.handleOptimisticLockingFailureException(
            OptimisticLockingFailureException("stale document")
        )

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body?.code).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT.code)
        assertThat(response.body?.retryable).isTrue()
    }

    @Test
    @DisplayName("MVC 응답에서도 낙관적 잠금 충돌을 일반 500으로 처리하지 않는다")
    fun `renders optimistic locking conflict as retryable 409 json`() {
        val mockMvc = MockMvcBuilders.standaloneSetup(ConflictController())
            .setControllerAdvice(exceptionHandler)
            .build()

        mockMvc.perform(get("/test/optimistic-lock-conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"))
            .andExpect(jsonPath("$.retryable").value(true))
    }

    @Test
    @DisplayName("외부 API 400 응답은 잘못된 요청으로 변환한다")
    fun `maps webclient 400 to invalid input`() {
        val response = exceptionHandler.handleWebClientResponseException(webClientException(400))

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo(ErrorCode.COMMON_INVALID_INPUT.code)
    }

    @Test
    @DisplayName("외부 API 429와 503 응답은 AI 서비스 혼잡으로 변환하고 원래 HTTP 상태를 유지한다")
    fun `maps webclient 429 and 503 to ai busy`() {
        val tooMany = exceptionHandler.handleWebClientResponseException(webClientException(429))
        val unavailable = exceptionHandler.handleWebClientResponseException(webClientException(503))

        assertThat(tooMany.statusCode.value()).isEqualTo(429)
        assertThat(tooMany.body?.code).isEqualTo(ErrorCode.AI_SERVICE_BUSY.code)
        assertThat(unavailable.statusCode.value()).isEqualTo(503)
        assertThat(unavailable.body?.code).isEqualTo(ErrorCode.AI_SERVICE_BUSY.code)
        assertThat(unavailable.body?.message).contains("일시적으로 사용할 수 없습니다")
    }

    @Test
    @DisplayName("그 밖의 외부 API 오류는 같은 HTTP 상태의 내부 오류 응답으로 변환한다")
    fun `maps other webclient errors to internal error`() {
        val response = exceptionHandler.handleWebClientResponseException(webClientException(502))

        assertThat(response.statusCode.value()).isEqualTo(502)
        assertThat(response.body?.code).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR.code)
        assertThat(response.body?.message).contains("status=502")
    }

    @Test
    @DisplayName("429 원인으로 재시도가 소진되면 AI 서비스 혼잡 503으로 응답한다")
    fun `maps retry exhausted by 429 to service unavailable`() {
        val exhausted = reactor.core.Exceptions.retryExhausted("retries exhausted", webClientException(429))

        val response = exceptionHandler.handleException(exhausted as Exception)

        assertThat(response.statusCode.value()).isEqualTo(503)
        assertThat(response.body?.code).isEqualTo(ErrorCode.AI_SERVICE_BUSY.code)
    }

    @Test
    @DisplayName("다른 원인으로 재시도가 소진되거나 예상치 못한 예외가 나면 500으로 응답한다")
    fun `maps other exhausted and unexpected errors to internal error`() {
        val exhausted = reactor.core.Exceptions.retryExhausted("retries exhausted", java.io.IOException("io"))

        val exhaustedResponse = exceptionHandler.handleException(exhausted as Exception)
        val unexpectedResponse = exceptionHandler.handleException(java.io.IOException("disk"))

        assertThat(exhaustedResponse.statusCode.value()).isEqualTo(500)
        assertThat(exhaustedResponse.body?.message).contains("외부 서비스 호출에 실패했습니다")
        assertThat(unexpectedResponse.statusCode.value()).isEqualTo(500)
        assertThat(unexpectedResponse.body?.message).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR.message)
    }

    @Test
    @DisplayName("도메인 예외를 각각의 오류 코드와 HTTP 상태로 변환한다")
    fun `maps domain exceptions`() {
        val illegalArgument = exceptionHandler.handleIllegalArgumentException(IllegalArgumentException("잘못된 값"))
        val illegalState = exceptionHandler.handleIllegalStateException(IllegalStateException("상태 오류"))
        val invalidPassword = exceptionHandler.handleInvalidPasswordException(InvalidPasswordException("규칙 위반"))
        val duplicateNickname = exceptionHandler.handleDuplicateNicknameException(DuplicateNicknameException())
        val quote = exceptionHandler.handleQuoteNotFoundException(QuoteNotFoundException("명언 없음"))
        val feedback = exceptionHandler.handleFeedbackNotFoundException(FeedbackNotFoundException("피드백 없음"))
        val timeout = exceptionHandler.handleAiGenerationTimeoutException(AiGenerationTimeoutException(12_000L))

        assertThat(illegalArgument.statusCode.value()).isEqualTo(400)
        assertThat(illegalArgument.body?.message).isEqualTo("잘못된 값")
        assertThat(illegalState.statusCode.value()).isEqualTo(500)
        assertThat(illegalState.body?.message).isEqualTo("상태 오류")
        assertThat(invalidPassword.body?.code).isEqualTo(ErrorCode.INVALID_PASSWORD.code)
        assertThat(invalidPassword.body?.message).isEqualTo("규칙 위반")
        assertThat(duplicateNickname.statusCode.value()).isEqualTo(ErrorCode.DUPLICATE_NICKNAME.status)
        assertThat(duplicateNickname.body?.code).isEqualTo(ErrorCode.DUPLICATE_NICKNAME.code)
        assertThat(quote.statusCode.value()).isEqualTo(404)
        assertThat(quote.body?.code).isEqualTo(ErrorCode.QUOTE_NOT_FOUND.code)
        assertThat(feedback.statusCode.value()).isEqualTo(404)
        assertThat(feedback.body?.message).isEqualTo("피드백 없음")
        assertThat(timeout.statusCode.value()).isEqualTo(ErrorCode.AI_GENERATION_TIMEOUT.status)
        assertThat(timeout.body?.code).isEqualTo(ErrorCode.AI_GENERATION_TIMEOUT.code)
    }

    @Test
    @DisplayName("메시지가 없는 IllegalArgumentException과 IllegalStateException은 기본 메시지를 사용한다")
    fun `uses default messages for message-less exceptions`() {
        val illegalArgument = exceptionHandler.handleIllegalArgumentException(IllegalArgumentException())
        val illegalState = exceptionHandler.handleIllegalStateException(IllegalStateException())

        assertThat(illegalArgument.body?.message).isEqualTo(ErrorCode.COMMON_INVALID_INPUT.message)
        assertThat(illegalState.body?.message).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR.message)
    }

    @Test
    @DisplayName("인증, 권한, 파라미터 타입 오류를 401, 403, 400으로 변환한다")
    fun `maps security and type mismatch exceptions`() {
        val mismatch = io.mockk.mockk<org.springframework.web.method.annotation.MethodArgumentTypeMismatchException>()
        io.mockk.every { mismatch.name } returns "period"
        io.mockk.every { mismatch.value } returns "YEARLY"

        val unauthorized = exceptionHandler.handleAuthenticationException(
            org.springframework.security.authentication.BadCredentialsException("bad token")
        )
        val forbidden = exceptionHandler.handleAccessDeniedException(
            org.springframework.security.access.AccessDeniedException("no role")
        )
        val badParameter = exceptionHandler.handleMethodArgumentTypeMismatchException(mismatch)

        assertThat(unauthorized.statusCode.value()).isEqualTo(401)
        assertThat(unauthorized.body?.code).isEqualTo(ErrorCode.UNAUTHORIZED.code)
        assertThat(forbidden.statusCode.value()).isEqualTo(403)
        assertThat(forbidden.body?.code).isEqualTo(ErrorCode.ACCESS_DENIED.code)
        assertThat(badParameter.statusCode.value()).isEqualTo(400)
        assertThat(badParameter.body?.message).isEqualTo("period: YEARLY 값이 올바르지 않습니다.")
    }

    private fun webClientException(status: Int) =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            status,
            "status $status",
            org.springframework.http.HttpHeaders.EMPTY,
            ByteArray(0),
            null
        )

    @RestController
    private class ConflictController {

        @GetMapping("/test/optimistic-lock-conflict")
        fun conflict(): Nothing {
            throw OptimisticLockingFailureException("stale document")
        }
    }
}
