package com.didimlog.application.problem.collector

/**
 * 모든 문제 배치 작업 상태를 공통 스키마로 반환하기 위한 응답 DTO
 */
data class JobStatusUnifiedResponse(
    val jobId: String,
    val jobType: ProblemJobType,
    val status: JobStatus,
    val queuedAt: Long,
    val startedAt: Long?,
    val lastHeartbeatAt: Long?,
    val completedAt: Long?,
    val totalCount: Int,
    val processedCount: Int,
    val successCount: Int,
    val failCount: Int,
    val progressPercentage: Int,
    val estimatedRemainingSeconds: Long?,
    val queuePosition: Int?,
    val range: JobRange?,
    val lastCheckpointId: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdBy: String,
    val targetManifest: ProblemJobTargetManifestReference? = null,
    val workerAttempt: ProblemJobWorkerAttempt? = null
)
