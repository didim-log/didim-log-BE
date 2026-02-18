package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminLogService
import com.didimlog.application.admin.LogCleanupMode
import com.didimlog.application.admin.LogCleanupService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.AdminLogResponse
import com.didimlog.ui.dto.LogCleanupPreviewResponse
import com.didimlog.ui.dto.LogCleanupResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "관리자 AI 리뷰 로그 조회 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/logs")
@Validated
class AdminLogController(
    private val adminLogService: AdminLogService,
    private val logCleanupService: LogCleanupService
) {

    @Operation(
        summary = "AI 리뷰 생성 로그 조회",
        description = "AI 리뷰 생성 로그를 페이징하여 조회합니다. BOJ ID로 필터링 가능합니다. ADMIN 권한이 필요합니다.",
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
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    fun getLogs(
        @Parameter(description = "BOJ ID 필터 (선택)")
        @RequestParam(required = false)
        bojId: String?,
        @Parameter(description = "페이지 번호 (기본값: 1)")
        @RequestParam(defaultValue = "1")
        @Positive(message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,
        @Parameter(description = "페이지 크기 (기본값: 20)")
        @RequestParam(defaultValue = "20")
        @Positive(message = "페이지 크기는 1 이상이어야 합니다.")
        size: Int
    ): ResponseEntity<Page<AdminLogResponse>> {
        val pageable: Pageable = PageRequest.of(
            page - 1, // Spring Data는 0-based 인덱스 사용
            size,
            Sort.by(Sort.Direction.DESC, "createdAt")
        )
        val logs = adminLogService.getLogs(bojId, pageable)
        val response = logs.map { AdminLogResponse.from(it) }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "특정 로그 상세 조회",
        description = "특정 로그의 상세 정보를 조회합니다. ADMIN 권한이 필요합니다.",
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
            ),
            ApiResponse(
                responseCode = "404",
                description = "로그를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{logId}")
    fun getLog(
        @Parameter(description = "로그 ID", required = true)
        @PathVariable
        @NotBlank(message = "로그 ID는 필수입니다.")
        logId: String
    ): ResponseEntity<AdminLogResponse> {
        val log = adminLogService.getLog(logId)
        return ResponseEntity.ok(AdminLogResponse.from(log))
    }

    @Operation(
        summary = "로그 정리 미리보기",
        description = "로그 정리 실행 전 삭제 대상 개수와 상태별 분포를 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "정리 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 olderThanDays 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
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
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/cleanup/preview")
    fun previewCleanupLogs(
        @Parameter(description = "정리 모드 (OLDER_THAN_DAYS | KEEP_RECENT_DAYS)")
        @RequestParam(defaultValue = "OLDER_THAN_DAYS")
        mode: LogCleanupMode,
        @Parameter(description = "mode=OLDER_THAN_DAYS에서 기준일")
        @RequestParam(required = false)
        olderThanDays: Int?,
        @Parameter(description = "mode=KEEP_RECENT_DAYS에서 유지일")
        @RequestParam(required = false)
        keepDays: Int?
    ): ResponseEntity<LogCleanupPreviewResponse> {
        val referenceDays = resolveReferenceDays(mode, olderThanDays, keepDays)
        val plan = logCleanupService.previewCleanup(mode, referenceDays)
        return ResponseEntity.ok(
            LogCleanupPreviewResponse(
                mode = plan.mode,
                referenceDays = plan.referenceDays,
                cutoffAt = plan.cutoffDate,
                deletableCount = plan.deletableCount,
                statusBreakdown = plan.statusBreakdown
            )
        )
    }

    @Operation(
        summary = "오래된 로그 정리 실행",
        description = "지정 모드/기준 일수에 따라 로그 정리를 실행합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/cleanup")
    fun cleanupLogs(
        @Parameter(description = "정리 모드 (OLDER_THAN_DAYS | KEEP_RECENT_DAYS)")
        @RequestParam(defaultValue = "OLDER_THAN_DAYS")
        mode: LogCleanupMode,
        @Parameter(description = "mode=OLDER_THAN_DAYS에서 기준일")
        @RequestParam(required = false)
        olderThanDays: Int?,
        @Parameter(description = "mode=KEEP_RECENT_DAYS에서 유지일")
        @RequestParam(required = false)
        keepDays: Int?
    ): ResponseEntity<LogCleanupResponse> {
        val referenceDays = resolveReferenceDays(mode, olderThanDays, keepDays)
        val result = logCleanupService.cleanupLogs(mode, referenceDays)
        val response = LogCleanupResponse(
            message = "${result.deletedCount}개의 로그가 삭제되었습니다.",
            mode = result.mode,
            referenceDays = result.referenceDays,
            cutoffAt = result.cutoffDate,
            deletedCount = result.deletedCount
        )
        return ResponseEntity.ok(response)
    }

    private fun resolveReferenceDays(mode: LogCleanupMode, olderThanDays: Int?, keepDays: Int?): Int {
        val referenceDays = when (mode) {
            LogCleanupMode.OLDER_THAN_DAYS -> olderThanDays
            LogCleanupMode.KEEP_RECENT_DAYS -> keepDays
        } ?: throw BusinessException(
            ErrorCode.COMMON_INVALID_INPUT,
            "mode=$mode 에 필요한 기준 일수가 누락되었습니다."
        )

        if (referenceDays <= 0) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "기준 일수는 1 이상이어야 합니다.")
        }
        return referenceDays
    }
}
