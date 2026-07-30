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

    @RestController
    private class ConflictController {

        @GetMapping("/test/optimistic-lock-conflict")
        fun conflict(): Nothing {
            throw OptimisticLockingFailureException("stale document")
        }
    }
}
