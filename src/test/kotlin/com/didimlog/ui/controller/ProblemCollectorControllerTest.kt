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

        // WebConfig를 제외하기 위해 RateLimitInterceptor 관련 빈을 모킹
        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
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
        every { problemCollectorService.collectMetadataAsync(1, 100) } returns "test-job-id-metadata"

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/collect-metadata")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("start", "1")
                .param("end", "100")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("문제 메타데이터 수집 작업이 시작되었습니다."))
            .andExpect(jsonPath("$.jobId").value("test-job-id-metadata"))
            .andExpect(jsonPath("$.range").value("1-100"))
    }

    @Test
    @DisplayName("문제 메타데이터 수집 작업 상태 조회 성공")
    fun `문제 메타데이터 수집 작업 상태 조회 성공`() {
        // given
        val jobId = "test-job-id-metadata"
        val status = com.didimlog.application.MetadataCollectJobStatus(
            jobId = jobId,
            status = com.didimlog.application.JobStatus.RUNNING,
            totalCount = 100,
            processedCount = 50,
            successCount = 48,
            failCount = 2,
            startProblemId = 1,
            endProblemId = 100,
            startedAt = 1704067200000,
            completedAt = null,
            errorMessage = null
        )
        every { problemCollectorService.getMetadataCollectJobStatus(jobId) } returns status

        // when & then
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/problems/collect-metadata/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.totalCount").value(100))
            .andExpect(jsonPath("$.processedCount").value(50))
            .andExpect(jsonPath("$.progressPercentage").value(50))
            .andExpect(jsonPath("$.startProblemId").value(1))
            .andExpect(jsonPath("$.endProblemId").value(100))

        verify(exactly = 1) { problemCollectorService.getMetadataCollectJobStatus(jobId) }
    }

    @Test
    @DisplayName("문제 메타데이터 수집 작업 상태 조회 - 작업 없음")
    fun `문제 메타데이터 수집 작업 상태 조회 작업 없음`() {
        // given
        val jobId = "non-existent-job-id"
        every { problemCollectorService.getMetadataCollectJobStatus(jobId) } returns null

        // when & then
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/problems/collect-metadata/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound())

        verify(exactly = 1) { problemCollectorService.getMetadataCollectJobStatus(jobId) }
    }

    @Test
    @DisplayName("상세 정보 크롤링 성공 시 200 OK 및 Response JSON 구조 검증")
    fun `상세 정보 크롤링 성공`() {
        // given
        every { problemCollectorService.collectDetailsBatchAsync() } returns "test-job-id-collect-details"

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/collect-details")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("문제 상세 정보 크롤링 작업이 시작되었습니다."))
            .andExpect(jsonPath("$.jobId").value("test-job-id-collect-details"))
    }

    @Test
    @DisplayName("언어 정보 최신화 성공 시 200 OK 및 Response JSON 구조 검증")
    fun `언어 정보 최신화 성공`() {
        // given
        every { problemCollectorService.updateLanguageBatchAsync() } returns "test-job-id-123"

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/update-language")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("문제 언어 정보 최신화 작업이 시작되었습니다."))
            .andExpect(jsonPath("$.jobId").value("test-job-id-123"))

        verify(exactly = 1) { problemCollectorService.updateLanguageBatchAsync() }
    }

    @Test
    @DisplayName("언어 정보 업데이트 작업 상태 조회 성공")
    fun `언어 정보 업데이트 작업 상태 조회 성공`() {
        // given
        val jobId = "test-job-id-123"
        val status = com.didimlog.application.LanguageUpdateJobStatus(
            jobId = jobId,
            status = com.didimlog.application.JobStatus.RUNNING,
            totalCount = 3400,
            processedCount = 150,
            successCount = 148,
            failCount = 2,
            startedAt = 1704067200000,
            completedAt = null,
            errorMessage = null
        )
        every { problemCollectorService.getLanguageUpdateJobStatus(jobId) } returns status

        // when & then
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/problems/update-language/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.totalCount").value(3400))
            .andExpect(jsonPath("$.processedCount").value(150))
            .andExpect(jsonPath("$.progressPercentage").value(4))

        verify(exactly = 1) { problemCollectorService.getLanguageUpdateJobStatus(jobId) }
    }

    @Test
    @DisplayName("언어 정보 업데이트 작업 상태 조회 - 작업 없음")
    fun `언어 정보 업데이트 작업 상태 조회 작업 없음`() {
        // given
        val jobId = "non-existent-job-id"
        every { problemCollectorService.getLanguageUpdateJobStatus(jobId) } returns null

        // when & then
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/problems/update-language/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound())

        verify(exactly = 1) { problemCollectorService.getLanguageUpdateJobStatus(jobId) }
    }

    @Test
    @DisplayName("문제 상세 정보 크롤링 성공 시 200 OK 및 Response JSON 구조 검증")
    fun `문제 상세 정보 크롤링 성공`() {
        // given
        every { problemCollectorService.collectDetailsBatchAsync() } returns "test-job-id-456"

        // when & then
        mockMvc.perform(
            post("/api/v1/admin/problems/collect-details")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("문제 상세 정보 크롤링 작업이 시작되었습니다."))
            .andExpect(jsonPath("$.jobId").value("test-job-id-456"))

        verify(exactly = 1) { problemCollectorService.collectDetailsBatchAsync() }
    }

    @Test
    @DisplayName("문제 상세 정보 수집 작업 상태 조회 성공")
    fun `문제 상세 정보 수집 작업 상태 조회 성공`() {
        // given
        val jobId = "test-job-id-456"
        val status = com.didimlog.application.DetailsCollectJobStatus(
            jobId = jobId,
            status = com.didimlog.application.JobStatus.RUNNING,
            totalCount = 100,
            processedCount = 50,
            successCount = 48,
            failCount = 2,
            startedAt = 1704067200000,
            completedAt = null,
            errorMessage = null
        )
        every { problemCollectorService.getDetailsCollectJobStatus(jobId) } returns status

        // when & then
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/problems/collect-details/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.totalCount").value(100))
            .andExpect(jsonPath("$.processedCount").value(50))
            .andExpect(jsonPath("$.progressPercentage").value(50))

        verify(exactly = 1) { problemCollectorService.getDetailsCollectJobStatus(jobId) }
    }

    @Test
    @DisplayName("문제 상세 정보 수집 작업 상태 조회 - 작업 없음")
    fun `문제 상세 정보 수집 작업 상태 조회 작업 없음`() {
        // given
        val jobId = "non-existent-job-id"
        every { problemCollectorService.getDetailsCollectJobStatus(jobId) } returns null

        // when & then
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/problems/collect-details/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound())

        verify(exactly = 1) { problemCollectorService.getDetailsCollectJobStatus(jobId) }
    }
}















