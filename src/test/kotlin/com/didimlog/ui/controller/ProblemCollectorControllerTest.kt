package com.didimlog.ui.controller

import com.didimlog.application.admin.ProblemStatsService
import com.didimlog.application.problem.collector.JobAuditResponse
import com.didimlog.application.problem.collector.JobMetricsResponse
import com.didimlog.application.problem.collector.JobMetricsWindow
import com.didimlog.application.problem.collector.JobPageResponse
import com.didimlog.application.problem.collector.JobRange
import com.didimlog.application.problem.collector.JobStatus
import com.didimlog.application.problem.collector.JobStatusUnifiedResponse
import com.didimlog.application.problem.collector.ProblemCollectorService
import com.didimlog.application.problem.collector.ProblemJobType
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

    @Autowired
    private lateinit var problemStatsService: ProblemStatsService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun problemCollectorService(): ProblemCollectorService = mockk(relaxed = true)

        @Bean
        fun problemStatsService(): ProblemStatsService = mockk(relaxed = true)

        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

        @Bean
        fun rateLimitService(): com.didimlog.global.ratelimit.RateLimitService = mockk(relaxed = true)

        @Bean
        fun rateLimitInterceptor(): com.didimlog.global.ratelimit.RateLimitInterceptor = mockk(relaxed = true)
    }

    @Test
    @DisplayName("메타데이터 수집 시 start가 0 이하일 때 400 Bad Request 반환")
    fun `메타데이터 수집 시 start 유효성 검증`() {
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
    @DisplayName("문제 통계 조회 성공")
    fun `문제 통계 조회 성공`() {
        every { problemStatsService.getProblemStats() } returns ProblemStatsService.ProblemStats(
            totalCount = 1000L,
            minProblemId = 1000,
            maxProblemId = 9999,
            minNullDescriptionHtmlProblemId = 1024,
            minNullLanguageProblemId = 2048
        )

        mockMvc.perform(
            get("/api/v1/admin/problems/stats")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalCount").value(1000))
            .andExpect(jsonPath("$.minProblemId").value(1000))
            .andExpect(jsonPath("$.maxProblemId").value(9999))
            .andExpect(jsonPath("$.minNullDescriptionHtmlProblemId").value(1024))
            .andExpect(jsonPath("$.minNullLanguageProblemId").value(2048))

        verify(exactly = 1) { problemStatsService.getProblemStats() }
    }

    @Test
    @DisplayName("메타데이터 수집 성공")
    fun `메타데이터 수집 성공`() {
        every { problemCollectorService.collectMetadataAsync(1, 100, any(), any()) } returns "test-job-id-metadata"

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
    @DisplayName("재시작 복구 중 작업 생성은 재시도 가능한 503을 반환")
    fun `작업 복구 중 메타데이터 수집 거절`() {
        every {
            problemCollectorService.collectMetadataAsync(1, 100, any(), any())
        } throws BusinessException(
            ErrorCode.WORKER_UNAVAILABLE,
            "문제 수집 작업 복구가 진행 중입니다. 잠시 후 다시 시도해주세요."
        )

        mockMvc.perform(
            post("/api/v1/admin/problems/collect-metadata")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("start", "1")
                .param("end", "100")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(true))
    }

    @Test
    @DisplayName("메타데이터 수집 상태 조회 성공")
    fun `메타데이터 수집 상태 조회 성공`() {
        val jobId = "job-1"
        every { problemCollectorService.getMetadataCollectJobStatus(jobId) } returns sampleJob(jobId, ProblemJobType.COLLECT_METADATA)

        mockMvc.perform(
            get("/api/v1/admin/problems/collect-metadata/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId))
            .andExpect(jsonPath("$.jobType").value("COLLECT_METADATA"))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.progressPercentage").value(50))
    }

    @Test
    @DisplayName("메타데이터 수집 상태 조회 작업 없음")
    fun `메타데이터 수집 상태 조회 작업 없음`() {
        val jobId = "missing"
        every { problemCollectorService.getMetadataCollectJobStatus(jobId) } returns null

        mockMvc.perform(
            get("/api/v1/admin/problems/collect-metadata/status/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
    }

    @Test
    @DisplayName("상세 정보 재수집 range 유효성 검증")
    fun `문제 상세 정보 재수집 range 유효성 검증`() {
        mockMvc.perform(
            post("/api/v1/admin/problems/refresh-details")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("start", "1000")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_RANGE"))
    }

    @Test
    @DisplayName("작업 목록 조회 성공")
    fun `작업 목록 조회 성공`() {
        val response = JobPageResponse.of(
            listOf(sampleJob("job-1", ProblemJobType.COLLECT_METADATA)),
            1,
            20,
            1
        )
        every {
            problemCollectorService.getJobs(
                type = ProblemJobType.COLLECT_METADATA,
                status = JobStatus.RUNNING,
                from = any(),
                to = any(),
                page = 1,
                size = 20
            )
        } returns response

        mockMvc.perform(
            get("/api/v1/admin/problems/jobs")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("type", "COLLECT_METADATA")
                .param("status", "RUNNING")
                .param("from", "1700000000")
                .param("to", "1800000000")
                .param("page", "1")
                .param("size", "20")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].jobId").value("job-1"))
            .andExpect(jsonPath("$.content[0].jobType").value("COLLECT_METADATA"))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    @Test
    @DisplayName("작업 단건 조회 성공")
    fun `작업 단건 조회 성공`() {
        val jobId = "job-100"
        every { problemCollectorService.getJob(jobId) } returns sampleJob(jobId, ProblemJobType.REFRESH_DETAILS)

        mockMvc.perform(
            get("/api/v1/admin/problems/jobs/$jobId")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value(jobId))
            .andExpect(jsonPath("$.jobType").value("REFRESH_DETAILS"))
    }

    @Test
    @DisplayName("작업 취소 성공")
    fun `작업 취소 성공`() {
        val jobId = "job-cancel"
        val cancelled = sampleJob(jobId, ProblemJobType.COLLECT_DETAILS).copy(
            status = JobStatus.CANCELLED,
            completedAt = 1700001111
        )
        every { problemCollectorService.cancelJob(jobId, any(), any()) } returns cancelled

        mockMvc.perform(
            post("/api/v1/admin/problems/jobs/$jobId/cancel")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    @DisplayName("작업 취소 시 이미 종료된 작업이면 409")
    fun `작업 취소 충돌`() {
        val jobId = "job-terminal"
        every { problemCollectorService.cancelJob(jobId, any(), any()) } throws BusinessException(
            ErrorCode.JOB_ALREADY_TERMINAL,
            "이미 종료된 작업입니다."
        )

        mockMvc.perform(
            post("/api/v1/admin/problems/jobs/$jobId/cancel")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("JOB_ALREADY_TERMINAL"))
    }

    @Test
    @DisplayName("작업 취소 CAS 충돌이 계속되면 재시도 가능한 409")
    fun `작업 취소 상태 변경 충돌`() {
        val jobId = "job-state-conflict"
        every { problemCollectorService.cancelJob(jobId, any(), any()) } throws BusinessException(
            ErrorCode.RESOURCE_STATE_CONFLICT,
            "작업 상태가 계속 변경되어 취소하지 못했습니다."
        )

        mockMvc.perform(
            post("/api/v1/admin/problems/jobs/$jobId/cancel")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"))
            .andExpect(jsonPath("$.retryable").value(true))
    }

    @Test
    @DisplayName("작업 재시도 성공")
    fun `작업 재시도 성공`() {
        val jobId = "job-retry"
        every { problemCollectorService.retryJob(jobId, any(), any()) } returns sampleJob("new-job", ProblemJobType.UPDATE_LANGUAGE)

        mockMvc.perform(
            post("/api/v1/admin/problems/jobs/$jobId/retry")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.jobId").value("new-job"))
    }

    @Test
    @DisplayName("작업 메트릭 조회 성공")
    fun `작업 메트릭 조회 성공`() {
        every { problemCollectorService.getJobMetrics(JobMetricsWindow.DAY) } returns JobMetricsResponse(
            window = JobMetricsWindow.DAY,
            totalJobs = 10,
            completedJobs = 7,
            failedJobs = 2,
            cancelledJobs = 1,
            averageDurationSeconds = 22,
            averageFailureRate = 0.11,
            topErrorCodes = emptyList()
        )

        mockMvc.perform(
            get("/api/v1/admin/problems/jobs/metrics")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
                .param("window", "DAY")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalJobs").value(10))
            .andExpect(jsonPath("$.failedJobs").value(2))
    }

    @Test
    @DisplayName("작업 감사 로그 조회 성공")
    fun `작업 감사 로그 조회 성공`() {
        val audit = JobAuditResponse.from(sampleJob("job-audit", ProblemJobType.COLLECT_DETAILS).copy(status = JobStatus.COMPLETED))
        every {
            problemCollectorService.getJobAudit(
                type = null,
                status = null,
                from = null,
                to = null,
                page = 1,
                size = 20
            )
        } returns JobPageResponse.of(listOf(audit), 1, 20, 1)

        mockMvc.perform(
            get("/api/v1/admin/problems/jobs/audit")
                .principal(org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, emptyList()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].jobId").value("job-audit"))
            .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
    }

    private fun sampleJob(jobId: String, type: ProblemJobType): JobStatusUnifiedResponse {
        return JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = type,
            status = JobStatus.RUNNING,
            queuedAt = 1700000000,
            startedAt = 1700000001,
            lastHeartbeatAt = 1700000010,
            completedAt = null,
            totalCount = 100,
            processedCount = 50,
            successCount = 49,
            failCount = 1,
            progressPercentage = 50,
            estimatedRemainingSeconds = 100,
            queuePosition = null,
            range = JobRange(1, 100),
            lastCheckpointId = "50",
            errorCode = null,
            errorMessage = null,
            createdBy = "admin"
        )
    }
}
