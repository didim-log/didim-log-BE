package com.didimlog.ui.controller

import com.didimlog.application.dashboard.DashboardService
import com.didimlog.ui.dto.DashboardResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Dashboard", description = "대시보드 관련 API")
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val dashboardService: DashboardService
) {

    @Operation(
        summary = "대시보드 조회",
        description = "학생의 오늘의 활동(오늘 푼 문제), 기본 프로필 정보, 랜덤 명언을 포함한 대시보드 정보를 조회합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
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
                responseCode = "404",
                description = "학생을 찾을 수 없음 또는 BOJ 인증이 완료되지 않은 사용자",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping
    fun getDashboard(
        authentication: Authentication
    ): ResponseEntity<DashboardResponse> {
        val bojId = authentication.name // JWT 토큰의 subject(bojId)
        val dashboardInfo = dashboardService.getDashboard(bojId)
        val response = DashboardResponse.from(dashboardInfo)
        return ResponseEntity.ok(response)
    }
}
