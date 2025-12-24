package com.didimlog.ui.controller

import com.didimlog.application.retrospective.RetrospectiveService
import com.didimlog.application.template.StaticTemplateService
import com.didimlog.global.auth.JwtTokenProvider
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

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        @Bean
        fun methodValidationPostProcessor(): org.springframework.validation.beanvalidation.MethodValidationPostProcessor {
            return org.springframework.validation.beanvalidation.MethodValidationPostProcessor()
        }
    }

    @Test
    @DisplayName("정적 템플릿 생성 요청 시 마크다운을 반환한다 (성공 케이스)")
    fun `정적 템플릿 생성 - 성공 케이스`() {
        // given
        val expectedTemplate = """
            # 🏆 A+B 해결 회고

            ## 1. 접근 방법 (Approach)

            - 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?
            - 풀이의 핵심 로직을 한 줄로 요약해 보세요.

            ## 2. 복잡도 분석 (Complexity)

            - 시간 복잡도: O(?)
            - 공간 복잡도: O(?)

            ## 제출한 코드

            ```python
            def solve(a, b):
                return a + b
            ```
        """.trimIndent()

        every { staticTemplateService.generateRetrospectiveTemplate(any(), any(), any(), any()) } returns expectedTemplate

        val body = mapOf(
            "code" to "def solve(a, b):\n    return a + b",
            "problemId" to "1000",
            "isSuccess" to true
        )

        // when & then
        mockMvc.perform(
            post("/api/v1/retrospectives/template/static")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").exists())
            .andExpect(jsonPath("$.template").value(expectedTemplate))
    }

    @Test
    @DisplayName("정적 템플릿 생성 요청 시 마크다운을 반환한다 (실패 케이스)")
    fun `정적 템플릿 생성 - 실패 케이스`() {
        // given
        val expectedTemplate = """
            # 💥 A+B 오답 노트

            ## 1. 실패 현상 (Symptom)

            - 어떤 종류의 에러가 발생했나요? (시간 초과, 메모리 초과, 틀렸습니다, 런타임 에러)
            - 테스트 케이스 중 통과하지 못한 예시가 있나요?

            ## 2. 나의 접근 (My Attempt)

            - 어떤 로직으로 풀려고 시도했나요?

            ## 제출한 코드

            ```python
            def solve(): pass
            ```

            ## 에러 로그

            ```text
            IndexError: list index out of range
            ```
        """.trimIndent()

        every { staticTemplateService.generateRetrospectiveTemplate(any(), any(), any(), any()) } returns expectedTemplate

        val body = mapOf(
            "code" to "def solve(): pass",
            "problemId" to "1000",
            "isSuccess" to false,
            "errorMessage" to "IndexError: list index out of range"
        )

        // when & then
        mockMvc.perform(
            post("/api/v1/retrospectives/template/static")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").exists())
            .andExpect(jsonPath("$.template").value(expectedTemplate))
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


