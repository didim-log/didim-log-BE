package com.didimlog.problem.application.collector

import com.didimlog.admin.application.AdminAuditService
import com.didimlog.admin.domain.AdminActionType
import org.slf4j.LoggerFactory
import java.util.concurrent.RejectedExecutionException

/**
 * 수집 작업 생성·취소·재시도를 관리자 감사 로그에 남긴다.
 *
 * 감사 로그 제출이 거절되어도 작업 처리는 계속한다.
 */
internal class ProblemJobAuditLogger(
    private val adminAuditService: AdminAuditService
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    fun logJobAction(
        actor: String,
        action: AdminActionType,
        ipAddress: String,
        job: JobStatusUnifiedResponse,
        extra: String? = null
    ) {
        val rangeText = if (job.range?.start != null && job.range.end != null) {
            "${job.range.start}-${job.range.end}"
        } else {
            "ALL"
        }

        val detail = buildString {
            append("jobId=${job.jobId}")
            append(", type=${job.jobType}")
            append(", status=${job.status}")
            append(", range=$rangeText")
            append(", total=${job.totalCount}")
            if (!extra.isNullOrBlank()) {
                append(", ")
                append(extra)
            }
        }

        try {
            adminAuditService.logAction(
                adminId = actor,
                action = action,
                details = detail,
                ipAddress = ipAddress
            )
        } catch (e: RejectedExecutionException) {
            log.error(
                "admin audit submission rejected: actor=$actor, action=$action, error=${e.message}",
                e
            )
        }
    }
}
