package com.didimlog.ui.controller

import com.didimlog.application.dashboard.DashboardService
import com.didimlog.ui.dto.DashboardResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Dashboard", description = "대시보드 관련 API")
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val dashboardService: DashboardService
) {

    @Operation(
        summary = "대시보드 조회",
        description = "학생의 현재 티어, 최근 풀이 기록, 추천 문제를 포함한 대시보드 정보를 조회합니다."
    )
    @GetMapping
    fun getDashboard(
        @Parameter(description = "학생 ID", required = true)
        @RequestParam studentId: String
    ): ResponseEntity<DashboardResponse> {
        val dashboardInfo = dashboardService.getDashboard(studentId)
        val response = DashboardResponse.from(dashboardInfo)
        return ResponseEntity.ok(response)
    }
}

