package com.didimlog.ui.dto

import com.didimlog.application.dashboard.DashboardInfo
import java.time.LocalDateTime

/**
 * 대시보드 응답 DTO
 */
data class DashboardResponse(
    val studentProfile: StudentProfileResponse,
    val todaySolvedCount: Int,
    val todaySolvedProblems: List<TodaySolvedProblemResponse>,
    val quote: QuoteResponse?,
    val currentTierTitle: String,
    val nextTierTitle: String,
    val currentRating: Int,
    val requiredRatingForNextTier: Int,
    val progressPercentage: Int
) {
    companion object {
        fun from(dashboardInfo: DashboardInfo): DashboardResponse {
            return DashboardResponse(
                studentProfile = StudentProfileResponse.from(dashboardInfo.studentProfile),
                todaySolvedCount = dashboardInfo.todaySolvedCount,
                todaySolvedProblems = dashboardInfo.todaySolvedProblems.map { TodaySolvedProblemResponse.from(it) },
                quote = dashboardInfo.quote?.let { QuoteResponse.from(it) },
                currentTierTitle = dashboardInfo.currentTierTitle,
                nextTierTitle = dashboardInfo.nextTierTitle,
                currentRating = dashboardInfo.currentRating,
                requiredRatingForNextTier = dashboardInfo.requiredRatingForNextTier,
                progressPercentage = dashboardInfo.progressPercentage
            )
        }
    }
}

/**
 * 학생 프로필 응답 DTO
 */
data class StudentProfileResponse(
    val nickname: String,
    val bojId: String,
    val currentTier: String,
    val currentTierLevel: Int,
    val consecutiveSolveDays: Int,
    val primaryLanguage: com.didimlog.domain.enums.PrimaryLanguage? = null
) {
    companion object {
        fun from(profile: com.didimlog.application.dashboard.StudentProfile): StudentProfileResponse {
            return StudentProfileResponse(
                nickname = profile.nickname,
                bojId = profile.bojId,
                currentTier = profile.currentTier.name,
                currentTierLevel = profile.currentTier.value,
                consecutiveSolveDays = profile.consecutiveSolveDays,
                primaryLanguage = profile.primaryLanguage
            )
        }
    }
}

/**
 * 오늘 푼 문제 응답 DTO
 */
data class TodaySolvedProblemResponse(
    val problemId: String,
    val result: String,
    val solvedAt: LocalDateTime
) {
    companion object {
        fun from(problem: com.didimlog.application.dashboard.TodaySolvedProblem): TodaySolvedProblemResponse {
            return TodaySolvedProblemResponse(
                problemId = problem.problemId,
                result = problem.result,
                solvedAt = problem.solvedAt
            )
        }
    }
}
