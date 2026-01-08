package com.didimlog.ui.dto

/**
 * 저장 공간 통계 응답 DTO
 */
data class StorageStatsResponse(
    val totalCount: Long,
    val estimatedSizeKb: Long,
    val oldestRecordDate: String // ISO 8601 형식 (YYYY-MM-DD)
)












