package com.didimlog.application

/**
 * 문제 상세 정보 수집 작업 상태를 나타내는 데이터 클래스
 */
data class DetailsCollectJobStatus(
    val jobId: String,
    val status: JobStatus,
    val totalCount: Int,
    val processedCount: Int,
    val successCount: Int,
    val failCount: Int,
    val startedAt: Long,
    val completedAt: Long? = null,
    val errorMessage: String? = null
) {
    val progressPercentage: Int
        get() = if (totalCount > 0) {
            (processedCount * 100 / totalCount).coerceAtMost(100)
        } else {
            0
        }

    val estimatedRemainingSeconds: Long?
        get() = if (status == JobStatus.RUNNING && processedCount > 0) {
            val avgTimePerProblem = 3L // 평균 3초 (2~4초 범위)
            val remaining = totalCount - processedCount
            remaining * avgTimePerProblem
        } else {
            null
        }
}


