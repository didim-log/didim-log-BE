package com.didimlog.problem.application.collector

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.withQueuePositions
import java.time.Duration
import kotlin.math.min

/**
 * 수집 작업의 단건·목록·메트릭·감사 조회.
 *
 * 대기(PENDING) 작업에는 대기 순번을 붙여 돌려준다. 목록을 읽을 때 상태 저장소가
 * 사라진 작업의 index를 정리하므로([ProblemJobStateStore.loadAllJobsPruningStaleIndex])
 * 조회도 Redis 쓰기를 일으킬 수 있다.
 */
internal class ProblemJobQueries(
    private val stateStore: ProblemJobStateStore
) {

    fun getJob(jobId: String): JobStatusUnifiedResponse? {
        val raw = stateStore.readJob(jobId) ?: return null
        return withQueuePosition(raw)
    }

    fun getTypedJob(jobId: String, type: ProblemJobType): JobStatusUnifiedResponse? {
        val job = getJob(jobId) ?: return null
        return if (job.jobType == type) job else null
    }

    fun getJobs(
        type: ProblemJobType?,
        status: JobStatus?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): JobPageResponse<JobStatusUnifiedResponse> {
        if (page <= 0 || size <= 0) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "page와 size는 1 이상이어야 합니다.")
        }
        if (from != null && to != null && from > to) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "from은 to보다 클 수 없습니다. from=$from, to=$to")
        }

        val filtered = withQueuePositions(stateStore.loadAllJobsPruningStaleIndex())
            .filter { job -> type == null || job.jobType == type }
            .filter { job -> status == null || job.status == status }
            .filter { job -> from == null || job.queuedAt >= from }
            .filter { job -> to == null || job.queuedAt <= to }
            .sortedByDescending { it.queuedAt }

        val offset = (page - 1) * size
        if (offset >= filtered.size) {
            return JobPageResponse.of(emptyList(), page, size, filtered.size.toLong())
        }

        val end = min(offset + size, filtered.size)
        return JobPageResponse.of(filtered.subList(offset, end), page, size, filtered.size.toLong())
    }

    fun getJobMetrics(window: JobMetricsWindow): JobMetricsResponse {
        val threshold = when (window) {
            JobMetricsWindow.DAY -> stateStore.nowEpochSeconds() - Duration.ofDays(1).seconds
            JobMetricsWindow.WEEK -> stateStore.nowEpochSeconds() - Duration.ofDays(7).seconds
            JobMetricsWindow.MONTH -> stateStore.nowEpochSeconds() - Duration.ofDays(30).seconds
        }

        val jobs = stateStore.loadAllJobsPruningStaleIndex().filter { it.queuedAt >= threshold }
        val totalJobs = jobs.size.toLong()
        val completedJobs = jobs.count { it.status == JobStatus.COMPLETED }.toLong()
        val failedJobs = jobs.count { it.status == JobStatus.FAILED }.toLong()
        val cancelledJobs = jobs.count { it.status == JobStatus.CANCELLED }.toLong()

        val averageDurationSeconds = jobs
            .mapNotNull { job ->
                val started = job.startedAt
                val completed = job.completedAt
                if (started == null || completed == null || completed < started) {
                    null
                } else {
                    completed - started
                }
            }
            .let { durations ->
                if (durations.isEmpty()) {
                    0L
                } else {
                    durations.sum() / durations.size
                }
            }

        val averageFailureRate = jobs
            .map { job ->
                if (job.totalCount <= 0) {
                    if (job.status == JobStatus.FAILED) 1.0 else 0.0
                } else {
                    job.failCount.toDouble() / job.totalCount.toDouble()
                }
            }
            .let { rates ->
                if (rates.isEmpty()) {
                    0.0
                } else {
                    rates.sum() / rates.size.toDouble()
                }
            }

        val topErrorCodes = jobs
            .mapNotNull { it.errorCode }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { JobErrorCodeMetric(it.key, it.value.toLong()) }

        return JobMetricsResponse(
            window = window,
            totalJobs = totalJobs,
            completedJobs = completedJobs,
            failedJobs = failedJobs,
            cancelledJobs = cancelledJobs,
            averageDurationSeconds = averageDurationSeconds,
            averageFailureRate = averageFailureRate,
            topErrorCodes = topErrorCodes
        )
    }

    fun getJobAudit(
        type: ProblemJobType?,
        status: JobStatus?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): JobPageResponse<JobAuditResponse> {
        val jobsPage = getJobs(type, status, from, to, page, size)
        val audits = jobsPage.content.map { JobAuditResponse.from(it) }
        return JobPageResponse(
            content = audits,
            page = jobsPage.page,
            size = jobsPage.size,
            totalElements = jobsPage.totalElements,
            totalPages = jobsPage.totalPages,
            hasNext = jobsPage.hasNext,
            hasPrevious = jobsPage.hasPrevious
        )
    }

    fun withQueuePosition(job: JobStatusUnifiedResponse): JobStatusUnifiedResponse {
        if (job.status != JobStatus.PENDING) {
            return job.copy(queuePosition = null)
        }

        val pending = stateStore.loadAllJobsPruningStaleIndex()
            .filter { it.status == JobStatus.PENDING }
            .sortedWith(compareBy<JobStatusUnifiedResponse> { it.queuedAt }.thenBy { it.jobId })

        val index = pending.indexOfFirst { it.jobId == job.jobId }
        return job.copy(queuePosition = if (index >= 0) index + 1 else null)
    }
}
