package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminLogService
import com.didimlog.application.admin.LogCleanupService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("AdminLogController 테스트")
@WebMvcTest(
    controllers = [AdminLogController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, AdminLogControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AdminLogControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminLogService: AdminLogService

    @Autowired
    private lateinit var logCleanupService: LogCleanupService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun adminLogService(): AdminLogService = mockk(relaxed = true)

        @Bean
        fun logCleanupService(): LogCleanupService = mockk(relaxed = true)

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("로그 정리 시 200 OK 및 삭제된 개수 반환")
    fun `로그 정리 성공`() {
        // given
        clearMocks(logCleanupService)
        val olderThanDays = 30
        val deletedCount = 100L

        every { logCleanupService.cleanupLogs(olderThanDays) } returns deletedCount

        // when & then
        mockMvc.perform(
            delete("/api/v1/admin/logs/cleanup")
                .param("olderThanDays", olderThanDays.toString())
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("100개의 로그가 삭제되었습니다."))
            .andExpect(jsonPath("$.deletedCount").value(100))

        verify(exactly = 1) { logCleanupService.cleanupLogs(olderThanDays) }
    }

    @Test
    @DisplayName("로그 정리 시 olderThanDays가 1 미만이면 400 Bad Request")
    fun `로그 정리 - 유효성 검사 실패`() {
        // when & then
        mockMvc.perform(
            delete("/api/v1/admin/logs/cleanup")
                .param("olderThanDays", "0")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }
}

