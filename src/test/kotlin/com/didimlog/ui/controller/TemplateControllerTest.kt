package com.didimlog.ui.controller

import com.didimlog.application.template.TemplateService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@DisplayName("TemplateController 테스트")
@WebMvcTest(
    controllers = [TemplateController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@AutoConfigureMockMvc(addFilters = false)
class TemplateControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var templateService: TemplateService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun templateService(): TemplateService = mockk(relaxed = true)

        @Bean
        fun studentRepository(): StudentRepository = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("시스템 또는 다른 사용자의 템플릿 수정은 HTTP 403으로 응답한다")
    fun `템플릿 수정 접근 거부 응답`() {
        val studentId = "student1"
        val templateId = "template1"
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))
        every {
            templateService.updateTemplate(templateId, studentId, "제목", "내용")
        } throws BusinessException(ErrorCode.ACCESS_DENIED)

        mockMvc.perform(
            put("/api/v1/templates/$templateId")
                .principal(UsernamePasswordAuthenticationToken(studentId, null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("title" to "제목", "content" to "내용")
                    )
                )
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code))

        verify(exactly = 1) {
            templateService.updateTemplate(templateId, studentId, "제목", "내용")
        }
    }

    @Test
    @DisplayName("템플릿 삭제 중 생명주기 충돌은 HTTP 409로 응답한다")
    fun `템플릿 삭제 충돌 응답`() {
        val studentId = "student1"
        val templateId = "template1"
        every { studentRepository.findById(studentId) } returns Optional.of(student(studentId))
        every {
            templateService.deleteTemplate(templateId, studentId)
        } throws BusinessException(ErrorCode.SESSION_STATE_CONFLICT)

        mockMvc.perform(
            delete("/api/v1/templates/$templateId")
                .principal(UsernamePasswordAuthenticationToken(studentId, null, emptyList()))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(ErrorCode.SESSION_STATE_CONFLICT.code))

        verify(exactly = 1) {
            templateService.deleteTemplate(templateId, studentId)
        }
    }

    private fun student(studentId: String): Student {
        return Student(
            id = studentId,
            nickname = Nickname("사용자1"),
            provider = Provider.BOJ,
            providerId = studentId,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }
}
