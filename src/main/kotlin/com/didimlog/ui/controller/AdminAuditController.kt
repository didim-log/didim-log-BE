package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.enums.AdminActionType
import com.didimlog.ui.dto.AdminAuditLogResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@Tag(name = "Admin Audit", description = "관리자 작업 감사 로그 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Validated
class AdminAuditController(
    private val adminAuditService: AdminAuditService
) {

    @Operation(
        summary = "관리자 작업 로그 조회",
        description = "관리자 작업 감사 로그를 조회합니다. ADMIN 권한이 필요합니다.",
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
    fun getAuditLogs(
        @Parameter(description = "페이지 번호 (1부터 시작, 기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Min(value = 1, message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,
        @Parameter(description = "페이지 크기 (기본값: 20)", required = false)
        @RequestParam(defaultValue = "20")
        @Positive(message = "페이지 크기는 1 이상이어야 합니다.")
        size: Int,
        @Parameter(description = "관리자 ID (선택)", required = false)
        @RequestParam(required = false)
        adminId: String?,
        @Parameter(description = "작업 타입 (선택)", required = false)
        @RequestParam(required = false)
        action: AdminActionType?,
        @Parameter(description = "시작 날짜 (선택, ISO 8601 형식)", required = false)
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        startDate: LocalDateTime?,
        @Parameter(description = "종료 날짜 (선택, ISO 8601 형식)", required = false)
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        endDate: LocalDateTime?
    ): ResponseEntity<Page<AdminAuditLogResponse>> {
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        
        val auditLogs = when {
            adminId != null -> adminAuditService.getAuditLogsByAdminId(adminId, pageable)
            action != null -> adminAuditService.getAuditLogsByAction(action, pageable)
            startDate != null && endDate != null -> adminAuditService.getAuditLogsByDateRange(startDate, endDate, pageable)
            else -> adminAuditService.getAuditLogs(pageable)
        }
        
        val response = auditLogs.map { AdminAuditLogResponse.from(it) }
        return ResponseEntity.ok(response)
    }
}


