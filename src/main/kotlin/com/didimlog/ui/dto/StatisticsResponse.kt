package com.didimlog.ui.dto

import com.didimlog.application.statistics.StatisticsInfo

/**
 * 통계 응답 DTO
 */
data class StatisticsResponse(
    val monthlyHeatmap: List<HeatmapDataResponse>,
    val categoryDistribution: Map<String, Int>,
    val algorithmCategoryDistribution: Map<String, Int>,
    val topUsedAlgorithms: List<TopUsedAlgorithmResponse>,
    val totalSolvedCount: Int
) {
    companion object {
        fun from(statisticsInfo: StatisticsInfo): StatisticsResponse {
            return StatisticsResponse(
                monthlyHeatmap = statisticsInfo.monthlyHeatmap.map { HeatmapDataResponse.from(it) },
                categoryDistribution = statisticsInfo.categoryDistribution,
                algorithmCategoryDistribution = statisticsInfo.algorithmCategoryDistribution,
                topUsedAlgorithms = statisticsInfo.topUsedAlgorithms.map { TopUsedAlgorithmResponse.from(it) },
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

/**
 * 가장 많이 사용한 알고리즘 응답 DTO
 */
data class TopUsedAlgorithmResponse(
    val name: String,
    val count: Int
) {
    companion object {
        fun from(topUsedAlgorithm: com.didimlog.application.statistics.TopUsedAlgorithm): TopUsedAlgorithmResponse {
            return TopUsedAlgorithmResponse(
                name = topUsedAlgorithm.name,
                count = topUsedAlgorithm.count
            )
        }
    }
}
