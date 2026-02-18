package com.didimlog.application.problem.collector

/**
 * 배치 작업 상태
 */
enum class JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
