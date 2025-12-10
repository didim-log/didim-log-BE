package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminDashboardService
import com.didimlog.ui.dto.AdminDashboardStatsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Dashboard", description = "관리자 대시보드 관련 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
class AdminDashboardController(
    private val adminDashboardService: AdminDashboardService
) {

    @Operation(
        summary = "관리자 대시보드 통계 조회",
        description = "총 회원 수, 오늘 가입한 회원 수, 총 해결된 문제 수, 오늘 작성된 회고 수를 조회합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @GetMapping("/stats")
    fun getDashboardStats(): ResponseEntity<AdminDashboardStatsResponse> {
        val stats = adminDashboardService.getDashboardStats()
        return ResponseEntity.ok(AdminDashboardStatsResponse.from(stats))
    }
}

