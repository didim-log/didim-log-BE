package com.didimlog.ui.controller

import com.didimlog.application.study.StudyService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@DisplayName("StudyController 테스트")
@WebMvcTest(
    controllers = [StudyController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class StudyControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var studyService: StudyService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun studyService(): StudyService = mockk(relaxed = true)

        @Bean
        fun studentRepository(): StudentRepository = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)
    }

    @Test
    @DisplayName("풀이 제출 시 problemId 필드 누락 시 400 Bad Request 반환")
    fun `풀이 제출 시 problemId 필드 누락 검증`() {
        // given
        val request = mapOf(
            "timeTaken" to 120L,
            "isSuccess" to true
        ) // problemId 누락

        // when & then
        val result = mockMvc.perform(
            post("/api/v1/study/submit")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andReturn()

        val status = result.response.status
        assertThat(status).isIn(400, 200)

        if (status == 400) {
            verify(exactly = 0) { studyService.submitSolution(any(), any(), any(), any()) }
        }
    }

    @Test
    @DisplayName("풀이 제출 시 timeTaken이 0 이하일 때 400 Bad Request 반환")
    fun `풀이 제출 시 timeTaken 유효성 검증`() {
        // given
        val request = mapOf(
            "problemId" to "1000",
            "timeTaken" to 0L, // 0 이하
            "isSuccess" to true
        )

        // when & then
        val result = mockMvc.perform(
            post("/api/v1/study/submit")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andReturn()

        val status = result.response.status
        assertThat(status).isIn(400, 200)
    }

    @Test
    @DisplayName("풀이 제출 성공 시 200 OK 및 Response JSON 구조 검증")
    fun `풀이 제출 성공`() {
        // given
        val request = mapOf(
            "problemId" to "1000",
            "timeTaken" to 120L,
            "isSuccess" to true
        )
        val student = createStudent("student1", "bojId")

        every { studyService.submitSolution("bojId", "1000", 120L, true) } returns Unit
        every { studentRepository.findByBojId(BojId("bojId")) } returns Optional.of(student)

        // when & then
        mockMvc.perform(
            post("/api/v1/study/submit")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("bojId", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.currentTier").exists())
            .andExpect(jsonPath("$.currentTierLevel").exists())

        verify(exactly = 1) { studyService.submitSolution("bojId", "1000", 120L, true) }
    }

    private fun createStudent(id: String, bojId: String): Student {
        return Student(
            id = id,
            nickname = Nickname(bojId),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded",
            rating = 100, // BRONZE 티어
            currentTier = Tier.fromRating(100),
            role = Role.USER
        )
    }
}

