package com.didimlog.ui.dto

import com.didimlog.application.statistics.StatisticsInfo

/**
 * 통계 응답 DTO
 * 백엔드에서 모든 집계 로직을 처리하여 프론트엔드에 전달한다.
 */
data class StatisticsResponse(
    val monthlyHeatmap: List<HeatmapDataResponse>,
    val totalSolved: Int,
    val totalRetrospectives: Long,
    val averageSolveTime: Double,
    val successRate: Double,
    val categoryStats: List<CategoryStatResponse>, // 성공한 문제의 카테고리별 통계 (Radar/Bar Chart용)
    val weaknessStats: List<CategoryStatResponse>  // 실패한 문제의 카테고리별 통계 (Weakness Analysis용)
) {
    companion object {
        fun from(statisticsInfo: StatisticsInfo): StatisticsResponse {
            return StatisticsResponse(
                monthlyHeatmap = statisticsInfo.monthlyHeatmap.map { HeatmapDataResponse.from(it) },
                totalSolved = statisticsInfo.totalSolvedCount,
                totalRetrospectives = statisticsInfo.totalRetrospectives,
                averageSolveTime = statisticsInfo.averageSolveTime,
                successRate = statisticsInfo.successRate,
                categoryStats = statisticsInfo.categoryStats.map { CategoryStatResponse.from(it) },
                weaknessStats = statisticsInfo.weaknessStats.map { CategoryStatResponse.from(it) }
            )
        }
    }
}

/**
 * 카테고리별 통계 응답 DTO
 */
data class CategoryStatResponse(
    val category: String,
    val count: Int
) {
    companion object {
        fun from(categoryStat: com.didimlog.application.statistics.CategoryStat): CategoryStatResponse {
            return CategoryStatResponse(
                category = categoryStat.category,
                count = categoryStat.count
            )
        }
    }
}

/**
 * 잔디 데이터 응답 DTO
 */
data class HeatmapDataResponse(
    val date: String,
    val count: Int,
    val problemIds: List<String>
) {
    companion object {
        fun from(heatmapData: com.didimlog.application.statistics.HeatmapData): HeatmapDataResponse {
            return HeatmapDataResponse(
                date = heatmapData.date,
                count = heatmapData.count,
                problemIds = heatmapData.problemIds
            )
        }
    }
}
