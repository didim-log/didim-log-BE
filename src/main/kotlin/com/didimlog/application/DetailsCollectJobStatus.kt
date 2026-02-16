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
        get() {
            val safeTotal = totalCount.coerceAtLeast(0)
            val safeProcessed = processedCount.coerceIn(0, safeTotal)
            if (safeTotal == 0) {
                return if (status == JobStatus.COMPLETED) 100 else 0
            }
            return (safeProcessed * 100 / safeTotal).coerceIn(0, 100)
        }

    val estimatedRemainingSeconds: Long?
        get() = if (status == JobStatus.RUNNING && processedCount > 0 && totalCount > 0) {
            val avgTimePerProblem = 3L // 평균 3초 (2~4초 범위)
            val remaining = (totalCount - processedCount).coerceAtLeast(0)
            remaining * avgTimePerProblem
        } else {
            null
        }
}

