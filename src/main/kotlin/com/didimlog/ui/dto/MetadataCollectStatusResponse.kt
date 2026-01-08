package com.didimlog.ui.dto

/**
 * 문제 메타데이터 수집 작업 상태 응답 DTO
 */
data class MetadataCollectStatusResponse(
    val jobId: String,
    val status: String, // PENDING, RUNNING, COMPLETED, FAILED
    val totalCount: Int,
    val processedCount: Int,
    val successCount: Int,
    val failCount: Int,
    val startProblemId: Int,
    val endProblemId: Int,
    val progressPercentage: Int,
    val estimatedRemainingSeconds: Long?,
    val startedAt: Long,
    val completedAt: Long?,
    val errorMessage: String?
)

