package com.didimlog.ui.controller

import com.didimlog.application.quote.QuoteService
import com.didimlog.domain.Quote
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.didimlog.global.auth.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat

@DisplayName("QuoteController 테스트")
@WebMvcTest(
    controllers = [QuoteController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class QuoteControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var quoteService: QuoteService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun quoteService(): QuoteService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)
    }

    @Test
    @DisplayName("랜덤 명언 조회 성공")
    fun `랜덤 명언 조회 성공`() {
        // given
        val quote = Quote(id = "quote1", content = "테스트 명언", author = "테스트 작가")
        every { quoteService.getRandomQuote() } returns quote

        // when & then
        mockMvc.perform(
            get("/api/v1/quotes/random")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").value("테스트 명언"))
            .andExpect(jsonPath("$.author").value("테스트 작가"))
    }

    @Test
    @DisplayName("명언이 없으면 204 No Content를 반환한다")
    fun `랜덤 명언 조회 실패 - 명언 없음`() {
        // given
        every { quoteService.getRandomQuote() } returns null

        // when & then
        mockMvc.perform(
            get("/api/v1/quotes/random")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNoContent)
    }
}
