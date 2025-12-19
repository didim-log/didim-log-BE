package com.didimlog.ui.dto

import com.didimlog.application.admin.AdminDashboardStats

/**
 * 관리자 대시보드 통계 응답 DTO
 */
data class AdminDashboardStatsResponse(
    val totalUsers: Long,
    val todaySignups: Long,
    val totalSolvedProblems: Long,
    val todayRetrospectives: Long
) {
    companion object {
        fun from(stats: AdminDashboardStats): AdminDashboardStatsResponse {
            return AdminDashboardStatsResponse(
                totalUsers = stats.totalUsers,
                todaySignups = stats.todaySignups,
                totalSolvedProblems = stats.totalSolvedProblems,
                todayRetrospectives = stats.todayRetrospectives
            )
        }
    }
}






