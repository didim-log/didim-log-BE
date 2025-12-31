package com.didimlog.ui.controller

import com.didimlog.application.member.MemberService
import com.didimlog.global.exception.GlobalExceptionHandler
import com.didimlog.ui.dto.UpdateMyNicknameRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

@DisplayName("MemberController 테스트")
@WebMvcTest(
    controllers = [MemberController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, MemberControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun memberService(): MemberService = mockk(relaxed = true)
    }

    @Test
    @DisplayName("닉네임 사용 가능 여부 조회 - 200 OK (true/false 본문)")
    fun `닉네임 체크`() {
        every { memberService.isNicknameAvailable("user_01") } returns true

        mockMvc.perform(
            get("/api/v1/members/check-nickname")
                .param("nickname", "user_01")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(content().string("true"))
    }

    @Test
    @DisplayName("내 닉네임 변경 - 204 No Content")
    fun `내 닉네임 변경`() {
        every { memberService.updateMyNickname("me", "user_01") } returns Unit

        mockMvc.perform(
            patch("/api/v1/members/me/nickname")
                .principal(UsernamePasswordAuthenticationToken("me", null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateMyNicknameRequest("user_01")))
        )
            .andExpect(status().isNoContent)

        verify(exactly = 1) { memberService.updateMyNickname("me", "user_01") }
    }
}


