package com.didimlog.ui.controller

import com.didimlog.application.feedback.FeedbackService
import com.didimlog.domain.Feedback
import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.didimlog.global.auth.JwtTokenProvider
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("FeedbackController 테스트")
@WebMvcTest(
    controllers = [FeedbackController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class FeedbackControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var feedbackService: FeedbackService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @TestConfiguration
    class TestConfig {
        @Bean
        fun feedbackService(): FeedbackService = mockk(relaxed = true)

        @Bean
        fun studentRepository(): StudentRepository = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)
    }

    @Test
    @DisplayName("피드백 등록 성공")
    fun `피드백 등록 성공`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val student = Student(
            id = "student1",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = bojIdVo,
            password = "encoded",
            rating = 100,
            currentTier = Tier.fromRating(100),
            role = Role.USER
        )
        val feedback = Feedback(
            id = "feedback1",
            writerId = "student1",
            content = "버그 리포트입니다. 매우 긴 내용을 작성합니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.PENDING,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)
        every { feedbackService.createFeedback("student1", feedback.content, feedback.type) } returns feedback

        // when & then
        mockMvc.perform(
            post("/api/v1/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-token")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken(bojId, null, emptyList()))
                .content("""{"content":"${feedback.content}","type":"BUG"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("feedback1"))
            .andExpect(jsonPath("$.content").value(feedback.content))
            .andExpect(jsonPath("$.type").value("BUG"))
            .andExpect(jsonPath("$.status").value("PENDING"))

        verify(exactly = 1) { feedbackService.createFeedback("student1", feedback.content, feedback.type) }
    }

    @Test
    @DisplayName("피드백 내용이 10자 미만이면 400 Bad Request를 반환한다")
    fun `피드백 등록 유효성 검증 실패 - 내용 길이 부족`() {
        // given
        val bojId = "testuser"
        val bojIdVo = BojId(bojId)
        val student = Student(
            id = "student1",
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = bojIdVo,
            password = "encoded",
            rating = 100,
            currentTier = Tier.fromRating(100),
            role = Role.USER
        )

        every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student)

        // when & then
        mockMvc.perform(
            post("/api/v1/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-token")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken(bojId, null, emptyList()))
                .content("""{"content":"짧음","type":"BUG"}""")
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { feedbackService.createFeedback(any(), any(), any()) }
    }
}
