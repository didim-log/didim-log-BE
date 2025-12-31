package com.didimlog.ui.controller

import com.didimlog.application.member.AdminMemberService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.didimlog.ui.dto.AdminMemberUpdateRequest
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("AdminMemberController 테스트")
@WebMvcTest(
    controllers = [AdminMemberController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, AdminMemberControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AdminMemberControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminMemberService: AdminMemberService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun adminMemberService(): AdminMemberService = mockk(relaxed = true)
    }

    @Test
    @DisplayName("관리자 회원 수정 - 204 No Content")
    fun `관리자 회원 수정`() {
        every { adminMemberService.updateMember("member-1", any(), any()) } returns Unit

        mockMvc.perform(
            put("/api/v1/admin/members/member-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AdminMemberUpdateRequest(nickname = "user_01", password = "pw1234!")))
        )
            .andExpect(status().isNoContent)

        verify(exactly = 1) { adminMemberService.updateMember("member-1", "user_01", "pw1234!") }
    }
}


