package com.didimlog.ui.dto

import com.didimlog.application.dashboard.DashboardInfo
import com.didimlog.domain.Problem
import com.didimlog.domain.Solution

/**
 * 대시보드 응답 DTO
 */
data class DashboardResponse(
    val currentTier: String,
    val currentTierLevel: Int,
    val recentSolutions: List<SolutionResponse>,
    val recommendedProblems: List<ProblemResponse>
) {
    companion object {
        fun from(dashboardInfo: DashboardInfo): DashboardResponse {
            return DashboardResponse(
                currentTier = dashboardInfo.currentTier.name,
                currentTierLevel = dashboardInfo.currentTier.value,
                recentSolutions = dashboardInfo.recentSolutions.map { SolutionResponse.from(it) },
                recommendedProblems = dashboardInfo.recommendedProblems.map { ProblemResponse.from(it) }
            )
        }
    }
}

