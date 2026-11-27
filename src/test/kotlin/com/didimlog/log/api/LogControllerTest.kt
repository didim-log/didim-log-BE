package com.didimlog.log.api

import com.didimlog.log.application.LogService
import com.didimlog.log.domain.Log
import com.didimlog.student.domain.Student
import com.didimlog.log.domain.AiFeedbackStatus
import com.didimlog.student.domain.Provider
import com.didimlog.student.domain.Role
import com.didimlog.student.domain.Tier
import com.didimlog.student.domain.repository.StudentRepository
import com.didimlog.student.domain.BojId
import com.didimlog.log.domain.LogCode
import com.didimlog.log.domain.LogContent
import com.didimlog.log.domain.LogTitle
import com.didimlog.student.domain.Nickname
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

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

    @Autowired
    private lateinit var aiReviewService: com.didimlog.log.application.AiReviewService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @TestConfiguration
    class TestConfig {
        @Bean
        fun logService(): LogService {
            val mock = mockk<LogService>(relaxed = true)
            return mock
        }

        @Bean
        fun aiReviewService(): com.didimlog.log.application.AiReviewService = mockk(relaxed = true)

        @Bean
        fun aiUsageService(): com.didimlog.ai.application.AiUsageService = mockk(relaxed = true)

        @Bean
        fun studentRepository(): com.didimlog.student.domain.repository.StudentRepository = mockk(relaxed = true)

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
            logService.createLog(any(), any(), any(), any(), any(), any())
        } returns savedLog
        every { studentRepository.findById("student-id") } returns Optional.of(student("student-id", "user123"))

        val authentication = UsernamePasswordAuthenticationToken(
            "student-id",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            post("/api/v1/logs")
                .principal(authentication)
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
                "student-id",
                "user123",
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
            "student-id",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            post("/api/v1/logs")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("AI 리뷰 생성 시작 시 202 Accepted 를 반환한다")
    fun `ai 리뷰 생성 시작`() {
        every { studentRepository.findById("student-id") } returns Optional.of(student("student-id", "user123"))
        every {
            aiReviewService.requestOneLineReviewAsync("log-1", "student-id")
        } returns com.didimlog.log.application.AiReviewResult(
            review = "AI 리뷰 생성 중입니다. 잠시 후 다시 시도해주세요.",
            cached = false,
            inProgress = true
        )

        val authentication = UsernamePasswordAuthenticationToken(
            "student-id",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            post("/api/v1/logs/log-1/ai-review")
                .principal(authentication)
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.cached").value(false))
            .andExpect(jsonPath("$.inProgress").value(true))
    }

    @Test
    @DisplayName("로그 템플릿 조회 성공 시 200 + template 반환")
    fun `로그 템플릿 조회 성공`() {
        every { studentRepository.findById("student-id") } returns Optional.of(student("student-id", "user123"))
        every { logService.getLogTemplate("log-1", "student-id") } returns "템플릿 본문"

        val authentication = UsernamePasswordAuthenticationToken(
            "student-id",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            get("/api/v1/logs/log-1/template")
                .principal(authentication)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").value("템플릿 본문"))

        verify(exactly = 1) { logService.getLogTemplate("log-1", "student-id") }
    }

    @Test
    @DisplayName("AI 리뷰 피드백 제출 시 불변 학생 ID를 서비스에 전달한다")
    fun `AI 리뷰 피드백 제출 성공`() {
        every { studentRepository.findById("owner-id") } returns Optional.of(student("owner-id", "owner1"))
        every {
            logService.updateFeedback("log-1", "owner-id", AiFeedbackStatus.LIKE, null)
        } returns mockk(relaxed = true)
        val authentication = UsernamePasswordAuthenticationToken(
            "owner-id",
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        mockMvc.perform(
            post("/api/v1/logs/log-1/feedback")
                .principal(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"LIKE"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("피드백이 제출되었습니다."))

        verify(exactly = 1) {
            logService.updateFeedback("log-1", "owner-id", AiFeedbackStatus.LIKE, null)
        }
    }

    private fun student(studentId: String, bojId: String): Student {
        return Student(
            id = studentId,
            nickname = Nickname("${bojId}_nick"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }
}
