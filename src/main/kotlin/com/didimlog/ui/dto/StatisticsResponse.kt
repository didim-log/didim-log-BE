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
    val totalSolvedCount: Int,
    val totalRetrospectives: Long,
    val averageSolveTime: Double,
    val successRate: Double,
    val tagRadarData: List<TagStatResponse>,
    val weaknessAnalysis: WeaknessAnalysisResponse?
) {
    companion object {
        fun from(statisticsInfo: StatisticsInfo): StatisticsResponse {
            return StatisticsResponse(
                monthlyHeatmap = statisticsInfo.monthlyHeatmap.map { HeatmapDataResponse.from(it) },
                categoryDistribution = statisticsInfo.categoryDistribution,
                algorithmCategoryDistribution = statisticsInfo.algorithmCategoryDistribution,
                topUsedAlgorithms = statisticsInfo.topUsedAlgorithms.map { TopUsedAlgorithmResponse.from(it) },
                totalSolvedCount = statisticsInfo.totalSolvedCount,
                totalRetrospectives = statisticsInfo.totalRetrospectives,
                averageSolveTime = statisticsInfo.averageSolveTime,
                successRate = statisticsInfo.successRate,
                tagRadarData = statisticsInfo.tagRadarData.map { TagStatResponse.from(it) },
                weaknessAnalysis = statisticsInfo.weaknessAnalysis?.let { WeaknessAnalysisResponse.from(it) }
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

/**
 * 태그별 통계 응답 DTO (레이더 차트용)
 */
data class TagStatResponse(
    val tag: String,
    val count: Int,
    val fullMark: Int
) {
    companion object {
        fun from(tagStat: com.didimlog.application.statistics.TagStat): TagStatResponse {
            return TagStatResponse(
                tag = tagStat.tag,
                count = tagStat.count,
                fullMark = tagStat.fullMark
            )
        }
    }
}

/**
 * 취약점 분석 응답 DTO
 */
data class WeaknessAnalysisResponse(
    val totalFailures: Int,
    val topCategory: String?,
    val topCategoryCount: Int,
    val topReason: String?,
    val categoryFailures: List<CategoryFailureResponse>
) {
    companion object {
        fun from(weaknessAnalysis: com.didimlog.application.statistics.WeaknessAnalysis): WeaknessAnalysisResponse {
            return WeaknessAnalysisResponse(
                totalFailures = weaknessAnalysis.totalFailures,
                topCategory = weaknessAnalysis.topCategory,
                topCategoryCount = weaknessAnalysis.topCategoryCount,
                topReason = weaknessAnalysis.topReason?.name,
                categoryFailures = weaknessAnalysis.categoryFailures.map { CategoryFailureResponse.from(it) }
            )
        }
    }
}

/**
 * 카테고리별 실패 응답 DTO
 */
data class CategoryFailureResponse(
    val category: String,
    val count: Int
) {
    companion object {
        fun from(categoryFailure: com.didimlog.application.statistics.CategoryFailure): CategoryFailureResponse {
            return CategoryFailureResponse(
                category = categoryFailure.category,
                count = categoryFailure.count
            )
        }
    }
}
