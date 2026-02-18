package com.didimlog.ui.controller

import com.didimlog.application.admin.ProblemStatsService
import com.didimlog.application.problem.collector.JobAuditResponse
import com.didimlog.application.problem.collector.JobMetricsResponse
import com.didimlog.application.problem.collector.JobMetricsWindow
import com.didimlog.application.problem.collector.JobPageResponse
import com.didimlog.application.problem.collector.JobStatus
import com.didimlog.application.problem.collector.JobStatusUnifiedResponse
import com.didimlog.application.problem.collector.ProblemCollectorService
import com.didimlog.application.problem.collector.ProblemJobType
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.ProblemStatsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "관리자 문제 수집/관리 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/problems")
@Validated
class ProblemCollectorController(
    private val problemCollectorService: ProblemCollectorService,
    private val problemStatsService: ProblemStatsService
) {

    @Operation(
        summary = "문제 통계 조회",
        description = "문제 컬렉션의 총 개수와 수집 진행에 필요한 최소/최대 문제 ID 정보를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/stats")
    fun getProblemStats(authentication: Authentication): ResponseEntity<ProblemStatsResponse> {
        val stats = problemStatsService.getProblemStats()
        return ResponseEntity.ok(ProblemStatsResponse.from(stats))
    }

    @Operation(
        summary = "문제 메타데이터 수집 (비동기)",
        description = "Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 비동기로 수집합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/collect-metadata")
    fun collectMetadata(
        authentication: Authentication,
        request: HttpServletRequest,
        @Parameter(description = "시작 문제 ID", required = true)
        @RequestParam
        @Positive(message = "시작 문제 ID는 1 이상이어야 합니다.")
        start: Int,
        @Parameter(description = "종료 문제 ID (포함)", required = true)
        @RequestParam
        @Positive(message = "종료 문제 ID는 1 이상이어야 합니다.")
        end: Int
    ): ResponseEntity<Map<String, String>> {
        val jobId = problemCollectorService.collectMetadataAsync(
            start = start,
            end = end,
            createdBy = authentication.name,
            ipAddress = request.remoteAddr ?: "unknown"
        )
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 메타데이터 수집 작업이 시작되었습니다.",
                "jobId" to jobId,
                "range" to "$start-$end"
            )
        )
    }

    @Operation(
        summary = "문제 메타데이터 수집 작업 상태 조회",
        description = "문제 메타데이터 수집 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/collect-metadata/status/{jobId}")
    fun getMetadataCollectJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val status = problemCollectorService.getMetadataCollectJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }

    @Operation(
        summary = "문제 상세 정보 크롤링 (비동기)",
        description = "DB에서 description이 null인 문제들의 상세 정보를 비동기로 수집합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/collect-details")
    fun collectDetails(
        authentication: Authentication,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, String>> {
        val jobId = problemCollectorService.collectDetailsBatchAsync(
            createdBy = authentication.name,
            ipAddress = request.remoteAddr ?: "unknown"
        )
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 상세 정보 크롤링 작업이 시작되었습니다.",
                "jobId" to jobId
            )
        )
    }

    @Operation(
        summary = "문제 상세 정보 수집 작업 상태 조회",
        description = "문제 상세 정보 수집 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/collect-details/status/{jobId}")
    fun getDetailsCollectJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val status = problemCollectorService.getDetailsCollectJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }

    @Operation(
        summary = "문제 상세 정보 재수집 (비동기)",
        description = "기존에 수집된 문제도 포함해 상세 정보를 강제로 다시 크롤링하여 갱신합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/refresh-details")
    fun refreshDetails(
        authentication: Authentication,
        request: HttpServletRequest,
        @Parameter(description = "시작 문제 ID (선택, end와 함께 제공)")
        @RequestParam(required = false)
        @Min(value = 1, message = "시작 문제 ID는 1 이상이어야 합니다.")
        start: Int?,
        @Parameter(description = "종료 문제 ID (선택, start와 함께 제공)")
        @RequestParam(required = false)
        @Min(value = 1, message = "종료 문제 ID는 1 이상이어야 합니다.")
        end: Int?
    ): ResponseEntity<Map<String, String>> {
        validateRange(start, end)
        val jobId = problemCollectorService.refreshDetailsBatchAsync(
            start = start,
            end = end,
            createdBy = authentication.name,
            ipAddress = request.remoteAddr ?: "unknown"
        )
        val response = mutableMapOf(
            "message" to "문제 상세 정보 재수집 작업이 시작되었습니다.",
            "jobId" to jobId
        )
        if (start != null && end != null) {
            response["range"] = "$start-$end"
        }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "문제 상세 정보 재수집 작업 상태 조회",
        description = "문제 상세 정보 재수집(강제 갱신) 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/refresh-details/status/{jobId}")
    fun getDetailsRefreshJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val status = problemCollectorService.getDetailsRefreshJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }

    @Operation(
        summary = "문제 언어 정보 최신화 (비동기)",
        description = "DB의 전체 문제를 대상으로 언어 판별을 다시 수행해 언어 정보를 보정합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/update-language")
    fun updateLanguage(
        authentication: Authentication,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, String>> {
        val jobId = problemCollectorService.updateLanguageBatchAsync(
            createdBy = authentication.name,
            ipAddress = request.remoteAddr ?: "unknown"
        )
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 언어 정보 최신화 작업이 시작되었습니다.",
                "jobId" to jobId
            )
        )
    }

    @Operation(
        summary = "언어 정보 업데이트 작업 상태 조회",
        description = "언어 정보 업데이트 작업의 진행 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/update-language/status/{jobId}")
    fun getLanguageUpdateJobStatus(
        authentication: Authentication,
        @Parameter(description = "작업 ID", required = true)
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val status = problemCollectorService.getLanguageUpdateJobStatus(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(status)
    }

    @Operation(
        summary = "문제 배치 작업 목록 조회",
        description = "문제 배치 작업 목록을 타입/상태/기간으로 필터링하여 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/jobs")
    fun getJobs(
        authentication: Authentication,
        @RequestParam(required = false) type: ProblemJobType?,
        @RequestParam(required = false) status: JobStatus?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "20") @Positive size: Int
    ): ResponseEntity<JobPageResponse<JobStatusUnifiedResponse>> {
        val response = problemCollectorService.getJobs(type, status, from, to, page, size)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "문제 배치 작업 단건 조회",
        description = "작업 ID로 배치 단건 상태를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/jobs/{jobId}")
    fun getJob(
        authentication: Authentication,
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val job = problemCollectorService.getJob(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
        return ResponseEntity.ok(job)
    }

    @Operation(
        summary = "문제 배치 작업 취소",
        description = "PENDING/RUNNING 상태 작업을 취소합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/jobs/{jobId}/cancel")
    fun cancelJob(
        authentication: Authentication,
        request: HttpServletRequest,
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val response = problemCollectorService.cancelJob(
            jobId = jobId,
            cancelledBy = authentication.name,
            ipAddress = request.remoteAddr ?: "unknown"
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "문제 배치 작업 재시도",
        description = "기존 파라미터/체크포인트 기반으로 작업을 재실행합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/jobs/{jobId}/retry")
    fun retryJob(
        authentication: Authentication,
        request: HttpServletRequest,
        @PathVariable jobId: String
    ): ResponseEntity<JobStatusUnifiedResponse> {
        val response = problemCollectorService.retryJob(
            jobId = jobId,
            requestedBy = authentication.name,
            ipAddress = request.remoteAddr ?: "unknown"
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "문제 배치 운영 메트릭 조회",
        description = "DAY/WEEK/MONTH 윈도우 기준 배치 운영 지표를 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/jobs/metrics")
    fun getJobMetrics(
        authentication: Authentication,
        @RequestParam(defaultValue = "DAY") window: JobMetricsWindow
    ): ResponseEntity<JobMetricsResponse> {
        val response = problemCollectorService.getJobMetrics(window)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "문제 배치 감사 로그 조회",
        description = "누가/언제/어떤 범위/어떤 결과로 실행했는지 조회합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/jobs/audit")
    fun getJobAudit(
        authentication: Authentication,
        @RequestParam(required = false) type: ProblemJobType?,
        @RequestParam(required = false) status: JobStatus?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "20") @Positive size: Int
    ): ResponseEntity<JobPageResponse<JobAuditResponse>> {
        val response = problemCollectorService.getJobAudit(type, status, from, to, page, size)
        return ResponseEntity.ok(response)
    }

    private fun validateRange(start: Int?, end: Int?) {
        if ((start == null) != (end == null)) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start와 end는 함께 제공되어야 합니다.")
        }
        if (start != null && end != null && start > end) {
            throw BusinessException(
                ErrorCode.INVALID_RANGE,
                "start는 end보다 클 수 없습니다. start=$start, end=$end"
            )
        }
    }
}
