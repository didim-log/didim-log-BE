package com.didimlog.ui.controller

import com.didimlog.application.ProblemCollectorService
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.GlobalExceptionHandler
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
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@DisplayName("ProblemCollectorController 테스트")
@WebMvcTest(
    controllers = [ProblemCollectorController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class ProblemCollectorControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var problemCollectorService: ProblemCollectorService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun problemCollectorService(): ProblemCollectorService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)
    }

    @Test
    @DisplayName("메타데이터 수집 시 start가 0 이하일 때 400 Bad Request 반환")
    fun `메타데이터 수집 시 start 유효성 검증`() {
        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/collect-metadata")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("start", "0")
                .param("end", "100")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("메타데이터 수집 성공 시 200 OK 및 Response JSON 구조 검증")
    fun `메타데이터 수집 성공`() {
        // given
        every { problemCollectorService.collectMetadata(1, 100) } returns Unit

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/collect-metadata")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("start", "1")
                .param("end", "100")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("문제 메타데이터 수집이 완료되었습니다."))
            .andExpect(jsonPath("$.range").value("1-100"))

        verify(exactly = 1) { problemCollectorService.collectMetadata(1, 100) }
    }

    @Test
    @DisplayName("상세 정보 크롤링 성공 시 200 OK 및 Response JSON 구조 검증")
    fun `상세 정보 크롤링 성공`() {
        // given
        every { problemCollectorService.collectDetailsBatch() } returns Unit

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/collect-details")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("문제 상세 정보 크롤링이 완료되었습니다."))

        verify(exactly = 1) { problemCollectorService.collectDetailsBatch() }
    }
}





