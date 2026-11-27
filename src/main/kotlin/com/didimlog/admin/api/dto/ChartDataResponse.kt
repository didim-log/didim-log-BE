package com.didimlog.admin.api.dto

import com.didimlog.admin.application.ChartDataPoint

/**
 * 차트 데이터 응답 DTO
 */
data class ChartDataResponse(
    val data: List<ChartDataItem>
) {
    companion object {
        fun from(chartData: List<ChartDataPoint>): ChartDataResponse {
            return ChartDataResponse(
                data = chartData.map { point ->
                    ChartDataItem(
                        date = point.date,
                        value = point.value
                    )
                }
            )
        }
    }
}

/**
 * 차트 데이터 아이템
 */
data class ChartDataItem(
    val date: String,
    val value: Long
)













