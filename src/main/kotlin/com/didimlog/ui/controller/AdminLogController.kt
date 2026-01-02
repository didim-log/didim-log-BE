package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminLogService
import com.didimlog.application.admin.LogCleanupService
import com.didimlog.ui.dto.AdminLogResponse
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

@Tag(name = "Admin", description = "관리자 관련 API (ADMIN 권한 필요)")
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
        summary = "오래된 로그 정리",
        description = "지정된 일수 이상 된 로그를 삭제합니다. ADMIN 권한이 필요합니다.",
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
    @DeleteMapping("/cleanup")
    fun cleanupLogs(
        @Parameter(description = "기준일 (이보다 오래된 로그 삭제)", required = true)
        @RequestParam
        @Positive(message = "olderThanDays는 1 이상이어야 합니다.")
        olderThanDays: Int
    ): ResponseEntity<LogCleanupResponse> {
        val deletedCount = logCleanupService.cleanupLogs(olderThanDays)
        val response = LogCleanupResponse(
            message = "${deletedCount}개의 로그가 삭제되었습니다.",
            deletedCount = deletedCount
        )
        return ResponseEntity.ok(response)
    }
}

