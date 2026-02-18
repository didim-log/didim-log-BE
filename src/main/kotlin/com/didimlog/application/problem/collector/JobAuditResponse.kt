package com.didimlog.application.problem.collector

data class JobAuditResponse(
    val jobId: String,
    val jobType: ProblemJobType,
    val status: JobStatus,
    val createdBy: String,
    val queuedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val range: JobRange?,
    val totalCount: Int,
    val successCount: Int,
    val failCount: Int,
    val errorCode: String?,
    val errorMessage: String?
) {
    companion object {
        fun from(job: JobStatusUnifiedResponse): JobAuditResponse {
            return JobAuditResponse(
                jobId = job.jobId,
                jobType = job.jobType,
                status = job.status,
                createdBy = job.createdBy,
                queuedAt = job.queuedAt,
                startedAt = job.startedAt,
                completedAt = job.completedAt,
                range = job.range,
                totalCount = job.totalCount,
                successCount = job.successCount,
                failCount = job.failCount,
                errorCode = job.errorCode,
                errorMessage = job.errorMessage
            )
        }
    }
}
