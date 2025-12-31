package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminDashboardService
import com.didimlog.application.admin.PerformanceMetricsService
import com.didimlog.ui.dto.AdminDashboardStatsResponse
import com.didimlog.ui.dto.PerformanceMetricsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "관리자 관련 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@Validated
class AdminDashboardController(
    private val adminDashboardService: AdminDashboardService,
    private val performanceMetricsService: PerformanceMetricsService
) {

    @Operation(
        summary = "관리자 대시보드 통계 조회",
        description = "총 회원 수, 오늘 가입한 회원 수, 총 해결된 문제 수, 오늘 작성된 회고 수를 조회합니다. ADMIN 권한이 필요합니다.",
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
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/stats")
    fun getDashboardStats(): ResponseEntity<AdminDashboardStatsResponse> {
        val stats = adminDashboardService.getDashboardStats()
        return ResponseEntity.ok(AdminDashboardStatsResponse.from(stats))
    }

    @Operation(
        summary = "성능 메트릭 조회",
        description = "최근 30분~1시간 동안의 분당 요청 수(RPM)와 평균 응답 속도를 조회합니다. ADMIN 권한이 필요합니다.",
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
    @GetMapping("/metrics")
    fun getPerformanceMetrics(
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30")
        @Positive(message = "minutes는 1 이상이어야 합니다.")
        minutes: Int
    ): ResponseEntity<PerformanceMetricsResponse> {
        val metrics = performanceMetricsService.getPerformanceMetrics(minutes)
        return ResponseEntity.ok(PerformanceMetricsResponse.from(metrics))
    }
}

