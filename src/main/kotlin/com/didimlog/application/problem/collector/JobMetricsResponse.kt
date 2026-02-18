package com.didimlog.application.problem.collector

data class JobMetricsResponse(
    val window: JobMetricsWindow,
    val totalJobs: Long,
    val completedJobs: Long,
    val failedJobs: Long,
    val cancelledJobs: Long,
    val averageDurationSeconds: Long,
    val averageFailureRate: Double,
    val topErrorCodes: List<JobErrorCodeMetric>
)

data class JobErrorCodeMetric(
    val code: String,
    val count: Long
)
