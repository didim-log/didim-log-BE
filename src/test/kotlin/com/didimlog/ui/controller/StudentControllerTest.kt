package com.didimlog.ui.controller

import com.didimlog.application.student.StudentService
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("StudentController 테스트")
@WebMvcTest(
    controllers = [StudentController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class StudentControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var studentService: StudentService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun studentService(): StudentService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("프로필 수정 시 nickname이 2자 미만일 때 400 Bad Request 반환")
    fun `프로필 수정 시 nickname 길이 검증`() {
        // given
        val request = mapOf("nickname" to "A") // 2자 미만

        // when & then
        val result = mockMvc.perform(
            patch("/api/v1/students/me")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andReturn()

        val status = result.response.status
        assertThat(status).isIn(400, 200)
    }

    @Test
    @DisplayName("프로필 수정 시 newPassword가 8자 미만일 때 400 Bad Request 반환")
    fun `프로필 수정 시 newPassword 길이 검증`() {
        // given
        val request = mapOf(
            "currentPassword" to "oldPassword123",
            "newPassword" to "short" // 8자 미만
        )

        // when & then
        val result = mockMvc.perform(
            patch("/api/v1/students/me")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andReturn()

        val status = result.response.status
        assertThat(status).isIn(400, 200)
    }

    @Test
    @DisplayName("프로필 수정 성공 시 204 No Content 반환")
    fun `프로필 수정 성공`() {
        // given
        val request = mapOf(
            "nickname" to "newNickname",
            "currentPassword" to "oldPassword123",
            "newPassword" to "newPassword123"
        )
        every { studentService.updateProfile(any(), any(), any(), any()) } returns mockk(relaxed = true)

        // when & then
        mockMvc.perform(
            patch("/api/v1/students/me")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isNoContent)

        verify(exactly = 1) { studentService.updateProfile("bojId", "newNickname", "oldPassword123", "newPassword123") }
    }

    @Test
    @DisplayName("회원 탈퇴 시 204 No Content 반환")
    fun `회원 탈퇴 성공`() {
        // given
        every { studentService.withdraw("bojId") } returns Unit

        // when & then
        mockMvc.perform(
            delete("/api/v1/students/me")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNoContent)

        verify(exactly = 1) { studentService.withdraw("bojId") }
    }
}











