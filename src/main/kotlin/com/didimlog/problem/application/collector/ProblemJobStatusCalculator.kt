package com.didimlog.problem.application.collector

/**
 * 작업 상태의 파생 값(진행률, 남은 시간, 대기 순번)과 종료 여부를 계산한다.
 *
 * Redis나 시계에 의존하지 않는 순수 함수만 둔다.
 */
internal object ProblemJobStatusCalculator {
    private const val METADATA_AVG_SECONDS = 1L
    private const val DETAILS_AVG_SECONDS = 3L
    private const val LANGUAGE_AVG_SECONDS = 1L

    fun normalize(job: JobStatusUnifiedResponse): JobStatusUnifiedResponse {
        val safeTotal = job.totalCount.coerceAtLeast(0)
        val safeProcessed = job.processedCount.coerceIn(0, safeTotal)
        val safeSuccess = job.successCount.coerceIn(0, safeProcessed)
        val safeFail = job.failCount.coerceIn(0, safeProcessed)
        val progress = when {
            safeTotal == 0 && isTerminal(job.status) -> 100
            safeTotal == 0 -> 0
            else -> ((safeProcessed * 100) / safeTotal).coerceIn(0, 100)
        }

        val estimated = estimateRemaining(job.jobType, job.status, safeProcessed, safeTotal)

        return job.copy(
            totalCount = safeTotal,
            processedCount = safeProcessed,
            successCount = safeSuccess,
            failCount = safeFail,
            progressPercentage = progress,
            estimatedRemainingSeconds = estimated,
            queuePosition = null
        )
    }

    fun estimateRemaining(type: ProblemJobType, status: JobStatus, processed: Int, total: Int): Long? {
        if (status != JobStatus.RUNNING || total <= 0 || processed <= 0 || processed > total) {
            return null
        }

        val avg = when (type) {
            ProblemJobType.COLLECT_METADATA -> METADATA_AVG_SECONDS
            ProblemJobType.COLLECT_DETAILS,
            ProblemJobType.REFRESH_DETAILS -> DETAILS_AVG_SECONDS
            ProblemJobType.UPDATE_LANGUAGE -> LANGUAGE_AVG_SECONDS
        }
        return (total - processed).toLong() * avg
    }

    fun withQueuePositions(jobs: List<JobStatusUnifiedResponse>): List<JobStatusUnifiedResponse> {
        val pending = jobs
            .filter { it.status == JobStatus.PENDING }
            .sortedWith(compareBy<JobStatusUnifiedResponse> { it.queuedAt }.thenBy { it.jobId })

        val map = pending.mapIndexed { idx, job -> job.jobId to (idx + 1) }.toMap()
        return jobs.map { job ->
            job.copy(queuePosition = map[job.jobId])
        }
    }

    fun isTerminal(status: JobStatus): Boolean {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED
    }
}
