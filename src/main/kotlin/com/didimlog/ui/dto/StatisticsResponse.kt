package com.didimlog.ui.dto

import com.didimlog.application.statistics.StatisticsInfo

/**
 * 통계 응답 DTO
 */
data class StatisticsResponse(
    val monthlyHeatmap: List<HeatmapDataResponse>,
    val categoryDistribution: Map<String, Int>,
    val totalSolvedCount: Int
) {
    companion object {
        fun from(statisticsInfo: StatisticsInfo): StatisticsResponse {
            return StatisticsResponse(
                monthlyHeatmap = statisticsInfo.monthlyHeatmap.map { HeatmapDataResponse.from(it) },
                categoryDistribution = statisticsInfo.categoryDistribution,
                totalSolvedCount = statisticsInfo.totalSolvedCount
            )
        }
    }
}

/**
 * 잔디 데이터 응답 DTO
 */
data class HeatmapDataResponse(
    val date: String,
    val count: Int
) {
    companion object {
        fun from(heatmapData: com.didimlog.application.statistics.HeatmapData): HeatmapDataResponse {
            return HeatmapDataResponse(
                date = heatmapData.date,
                count = heatmapData.count
            )
        }
    }
}


