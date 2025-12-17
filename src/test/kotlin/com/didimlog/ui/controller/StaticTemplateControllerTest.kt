package com.didimlog.ui.controller

import com.didimlog.application.retrospective.RetrospectiveService
import com.didimlog.application.template.StaticTemplateService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("StaticTemplateController 테스트 (RetrospectiveController 내부)")
@WebMvcTest(
    controllers = [RetrospectiveController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class StaticTemplateControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var staticTemplateService: StaticTemplateService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun retrospectiveService(): RetrospectiveService = mockk(relaxed = true)

        @Bean
        fun staticTemplateService(): StaticTemplateService = mockk(relaxed = true)
    }

    @Test
    @DisplayName("정적 템플릿 생성 요청 시 마크다운을 반환한다 (성공 케이스)")
    fun `정적 템플릿 생성 - 성공 케이스`() {
        every { staticTemplateService.generateRetrospectiveTemplate(any(), any(), any(), any()) } returns "static template"

        val body = mapOf(
            "code" to "print(1 + 2)",
            "problemId" to "1000",
            "isSuccess" to true
        )

        mockMvc.perform(
            post("/api/v1/retrospectives/template/static")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").value("static template"))
    }

    @Test
    @DisplayName("정적 템플릿 생성 요청 시 마크다운을 반환한다 (실패 케이스)")
    fun `정적 템플릿 생성 - 실패 케이스`() {
        every { staticTemplateService.generateRetrospectiveTemplate(any(), any(), any(), any()) } returns "static template with error"

        val body = mapOf(
            "code" to "print(1 + 2)",
            "problemId" to "1000",
            "isSuccess" to false,
            "errorMessage" to "IndexError: list index out of range"
        )

        mockMvc.perform(
            post("/api/v1/retrospectives/template/static")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").value("static template with error"))
    }

    @Test
    @DisplayName("필수 필드가 누락되면 400 Bad Request를 반환한다")
    fun `필수 필드 누락 검증`() {
        val body = mapOf(
            "code" to "print(1)"
            // problemId, isSuccess 누락
        )

        mockMvc.perform(
            post("/api/v1/retrospectives/template/static")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isBadRequest)
    }
}
