package com.didimlog.ui.controller

import com.didimlog.application.log.LogService
import com.didimlog.domain.Log
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("LogController 테스트")
@WebMvcTest(
    controllers = [LogController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(LogControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class LogControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var logService: LogService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun logService(): LogService {
            val mock = mockk<LogService>(relaxed = true)
            return mock
        }

        @Bean
        fun aiReviewService(): com.didimlog.application.log.AiReviewService = mockk(relaxed = true)

        @Bean
        fun aiUsageService(): com.didimlog.application.ai.AiUsageService = mockk(relaxed = true)

        @Bean
        fun studentRepository(): com.didimlog.domain.repository.StudentRepository = mockk(relaxed = true)

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("로그 생성 성공 시 201 + logId 반환")
    fun `로그 생성 성공`() {
        val request = mapOf(
            "title" to "Problem 1000 Solution",
            "content" to "문제 풀이 회고",
            "code" to "public class Solution { }"
        )

        val savedLog = Log(
            id = "log-123",
            title = LogTitle("Problem 1000 Solution"),
            content = LogContent("문제 풀이 회고"),
            code = LogCode("public class Solution { }")
        )

        every {
            logService.createLog(any(), any(), any(), any(), any())
        } returns savedLog

        val authentication = UsernamePasswordAuthenticationToken(
            "user123",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            post("/api/v1/logs")
                .with(authentication(authentication))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("log-123"))

        verify(exactly = 1) {
            logService.createLog(
                "Problem 1000 Solution",
                "문제 풀이 회고",
                "public class Solution { }",
                any(),
                null
            )
        }
    }

    @Test
    @DisplayName("로그 생성 시 제목이 없으면 400 에러")
    fun `로그 생성 실패 - 제목 없음`() {
        val request = mapOf(
            "title" to "",
            "content" to "문제 풀이 회고",
            "code" to "public class Solution { }"
        )

        val authentication = UsernamePasswordAuthenticationToken(
            "user123",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            post("/api/v1/logs")
                .with(authentication(authentication))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
    }
}

