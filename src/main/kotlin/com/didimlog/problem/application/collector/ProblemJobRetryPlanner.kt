package com.didimlog.problem.application.collector

import com.didimlog.admin.domain.AdminActionType
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.isTerminal
import com.didimlog.problem.application.collector.ProblemJobTargets.dropManifestTargets
import com.didimlog.problem.application.collector.ProblemJobTargets.filterProblemsByRange
import com.didimlog.problem.application.collector.ProblemJobTargets.isAfterCheckpoint
import com.didimlog.problem.application.collector.ProblemJobTargets.orderProblems
import com.didimlog.problem.application.collector.ProblemJobTargets.targetIdAt
import com.didimlog.problem.application.collector.ProblemJobTargets.targetManifestConflict
import com.didimlog.problem.application.collector.ProblemJobTargets.targetPosition
import com.didimlog.problem.application.collector.ProblemJobTargets.validateRange
import com.didimlog.problem.domain.Problem
import com.didimlog.problem.domain.repository.ProblemRepository
import org.slf4j.LoggerFactory

/**
 * 종료된 작업의 실패 항목과 처리하지 못한 대상을 골라 새 작업으로 다시 실행한다.
 *
 * - manifest가 있는 작업: 실패 항목 원장과 체크포인트를 manifest 위치로 검증한 뒤
 *   실패 항목 + 처리하지 않은 나머지를 고른다
 * - manifest가 없는 옛 작업: 원본 범위·체크포인트와 현재 DB 상태로 대상을 고른다
 *
 * 다시 실행할 대상이 없으면 바로 완료되는 빈 작업을 만들고, 재시도 감사 로그를 남긴다.
 */
internal class ProblemJobRetryPlanner(
    private val problemRepository: ProblemRepository,
    private val stateStore: ProblemJobStateStore,
    private val manifestValidator: ProblemJobManifestValidator,
    private val auditLogger: ProblemJobAuditLogger,
    private val jobQueries: ProblemJobQueries,
    private val jobLauncher: ProblemJobLauncher
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    fun retryJob(jobId: String, requestedBy: String, ipAddress: String): JobStatusUnifiedResponse {
        val original = stateStore.readJob(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")

        if (!isTerminal(original.status)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "RUNNING/PENDING 작업은 재시도할 수 없습니다. jobId=$jobId")
        }

        val failureLedger = stateStore.readFailureLedger(jobId)
        val failedProblemIds = failureLedger.problemIds
        val targetManifest = manifestValidator.readTargetManifest(original)
        val interruptedNonMetadataJob = original.jobType != ProblemJobType.COLLECT_METADATA &&
            original.status != JobStatus.COMPLETED
        val usesLegacyRetryFallback = targetManifest == null &&
            !failureLedger.exists &&
            (original.failCount > 0 || interruptedNonMetadataJob)
        if (!usesLegacyRetryFallback && failedProblemIds.size != original.failCount) {
            throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "실패 항목 기록이 작업 상태와 일치하지 않습니다. jobId=$jobId, failCount=${original.failCount}, recorded=${failedProblemIds.size}"
            )
        }
        if (targetManifest != null) {
            return retryManifestJob(
                original = original,
                manifest = targetManifest,
                failedProblemIds = failedProblemIds,
                requestedBy = requestedBy,
                ipAddress = ipAddress
            )
        }

        val checkpointExclusive = original.lastCheckpointId?.toIntOrNull()
        val retryJobId = when (original.jobType) {
            ProblemJobType.COLLECT_METADATA -> {
                val start = original.range?.start
                    ?: throw BusinessException(ErrorCode.INVALID_RANGE, "원본 작업의 start 범위를 찾을 수 없습니다. jobId=$jobId")
                val end = original.range.end
                    ?: throw BusinessException(ErrorCode.INVALID_RANGE, "원본 작업의 end 범위를 찾을 수 없습니다. jobId=$jobId")
                validateRange(start, end)
                val failedIds = failedProblemIds.map { failedId ->
                    failedId.toIntOrNull()
                        ?: throw BusinessException(
                            ErrorCode.RESOURCE_STATE_CONFLICT,
                            "메타데이터 실패 항목 ID가 숫자가 아닙니다. jobId=$jobId, problemId=$failedId"
                        )
                }.sorted()
                if (failedIds.any { it !in start..end }) {
                    throw BusinessException(
                        ErrorCode.RESOURCE_STATE_CONFLICT,
                        "메타데이터 실패 항목 ID가 원본 범위를 벗어났습니다. jobId=$jobId"
                    )
                }

                when {
                    usesLegacyRetryFallback -> jobLauncher.startCollectMetadataWithManifest(
                        explicitTargetIds = emptyList(),
                        targetRange = JobRange(start, end),
                        responseRange = JobRange(start, end),
                        createdBy = requestedBy,
                        ipAddress = ipAddress
                    ).jobId

                    original.status == JobStatus.COMPLETED -> {
                        if (failedIds.isEmpty()) {
                            jobLauncher.createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            jobLauncher.startCollectMetadataWithManifest(
                                explicitTargetIds = failedIds,
                                targetRange = null,
                                responseRange = JobRange(failedIds.first(), failedIds.last()),
                                createdBy = requestedBy,
                                ipAddress = ipAddress
                            ).jobId
                        }
                    }

                    else -> {
                        val invalidFailedCheckpoint = when {
                            failedIds.isEmpty() -> false
                            checkpointExclusive == null -> true
                            else -> failedIds.any { failedId -> failedId > checkpointExclusive }
                        }
                        if (invalidFailedCheckpoint) {
                            throw BusinessException(
                                ErrorCode.RESOURCE_STATE_CONFLICT,
                                "메타데이터 실패 항목과 체크포인트가 일치하지 않습니다. jobId=$jobId"
                            )
                        }
                        val retryStart = when {
                            checkpointExclusive == null -> start
                            checkpointExclusive >= end -> null
                            else -> maxOf(start, checkpointExclusive + 1)
                        }
                        if (failedIds.isEmpty() && retryStart == null) {
                            jobLauncher.createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            jobLauncher.startCollectMetadataWithManifest(
                                explicitTargetIds = failedIds,
                                targetRange = retryStart?.let { JobRange(it, end) },
                                responseRange = JobRange(
                                    failedIds.firstOrNull() ?: requireNotNull(retryStart),
                                    if (retryStart == null) failedIds.last() else end
                                ),
                                createdBy = requestedBy,
                                ipAddress = ipAddress
                            ).jobId
                        }
                    }
                }
            }

            ProblemJobType.COLLECT_DETAILS -> {
                val candidates = loadRetryProblemCandidates(
                    problemRepository.findByDescriptionHtmlIsNull(),
                    failedProblemIds,
                    jobId
                )
                val targets = selectRetryProblems(
                    original,
                    candidates,
                    failedProblemIds,
                    checkpointExclusive,
                    usesLegacyRetryFallback
                )
                jobLauncher.startCollectDetailsWithTargets(
                    targets,
                    requestedBy,
                    ipAddress
                ).jobId
            }

            ProblemJobType.REFRESH_DETAILS -> {
                val range = original.range
                val candidates = loadRetryProblemCandidates(
                    filterProblemsByRange(problemRepository.findAll(), range?.start, range?.end),
                    failedProblemIds,
                    jobId
                )
                val targets = selectRetryProblems(
                    original,
                    candidates,
                    failedProblemIds,
                    checkpointExclusive,
                    usesLegacyRetryFallback
                )
                jobLauncher.startRefreshDetailsWithTargets(
                    targets,
                    requestedBy,
                    ipAddress,
                    range
                ).jobId
            }

            ProblemJobType.UPDATE_LANGUAGE -> {
                val candidates = loadRetryProblemCandidates(
                    problemRepository.findAll(),
                    failedProblemIds,
                    jobId
                )
                val targets = selectRetryProblems(
                    original,
                    candidates,
                    failedProblemIds,
                    checkpointExclusive,
                    usesLegacyRetryFallback
                )
                jobLauncher.startUpdateLanguageWithTargets(
                    targets,
                    requestedBy,
                    ipAddress
                ).jobId
            }
        }

        val retryJob = jobQueries.getJob(retryJobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "재시도 작업 생성에 실패했습니다. jobId=$retryJobId")

        auditLogger.logJobAction(
            requestedBy,
            AdminActionType.PROBLEM_JOB_RETRY,
            ipAddress,
            retryJob,
            "retryFrom=$jobId"
        )

        return retryJob
    }

    private fun retryManifestJob(
        original: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest,
        failedProblemIds: Set<String>,
        requestedBy: String,
        ipAddress: String
    ): JobStatusUnifiedResponse {
        val selected = selectManifestRetryTargets(
            original = original,
            manifest = manifest,
            failedProblemIds = failedProblemIds
        )
        val retryJobId = when (original.jobType) {
            ProblemJobType.COLLECT_METADATA -> {
                val explicitIds = selected.explicitIds.map { problemId ->
                    problemId.toIntOrNull()
                        ?: targetManifestConflict(
                            original.jobId,
                            "메타데이터 작업 대상 ID가 숫자가 아닙니다. problemId=$problemId"
                        )
                }
                if (explicitIds.isEmpty() && selected.range == null) {
                    jobLauncher.createNoopCompletedJob(
                        type = ProblemJobType.COLLECT_METADATA,
                        range = original.range,
                        createdBy = requestedBy
                    ).jobId
                } else {
                    jobLauncher.startCollectMetadataWithManifest(
                        explicitTargetIds = explicitIds,
                        targetRange = selected.range,
                        responseRange = metadataResponseRange(selected),
                        createdBy = requestedBy,
                        ipAddress = ipAddress
                    ).jobId
                }
            }

            ProblemJobType.COLLECT_DETAILS -> {
                val selectedProblems = loadManifestProblems(
                    targetIds = selected.explicitIds,
                    jobId = original.jobId
                ).filter { problem ->
                    failedProblemIds.contains(problem.id.value) ||
                        problem.descriptionHtml == null
                }
                jobLauncher.startCollectDetailsWithTargets(
                    selectedProblems,
                    requestedBy,
                    ipAddress
                ).jobId
            }

            ProblemJobType.REFRESH_DETAILS -> {
                val selectedProblems = loadManifestProblems(
                    targetIds = selected.explicitIds,
                    jobId = original.jobId
                )
                jobLauncher.startRefreshDetailsWithTargets(
                    selectedProblems,
                    requestedBy,
                    ipAddress,
                    original.range
                ).jobId
            }

            ProblemJobType.UPDATE_LANGUAGE -> {
                val selectedProblems = loadManifestProblems(
                    targetIds = selected.explicitIds,
                    jobId = original.jobId
                )
                jobLauncher.startUpdateLanguageWithTargets(
                    selectedProblems,
                    requestedBy,
                    ipAddress
                ).jobId
            }
        }

        val retryJob = jobQueries.getJob(retryJobId)
            ?: throw BusinessException(
                ErrorCode.JOB_NOT_FOUND,
                "재시도 작업 생성에 실패했습니다. jobId=$retryJobId"
            )
        auditLogger.logJobAction(
            requestedBy,
            AdminActionType.PROBLEM_JOB_RETRY,
            ipAddress,
            retryJob,
            "retryFrom=${original.jobId}"
        )
        return retryJob
    }

    private fun selectManifestRetryTargets(
        original: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest,
        failedProblemIds: Set<String>
    ): ManifestTargetSelection {
        val targetCount = manifestValidator.validateManifestForJob(original, manifest)
        if (original.processedCount !in 0..targetCount) {
            targetManifestConflict(
                original.jobId,
                "처리 수가 대상 수 범위를 벗어났습니다. processed=${original.processedCount}, total=$targetCount"
            )
        }

        if (original.processedCount == 0) {
            if (original.lastCheckpointId != null) {
                targetManifestConflict(original.jobId, "처리 전 작업에 체크포인트가 있습니다.")
            }
        } else {
            val expectedCheckpoint = targetIdAt(manifest, original.processedCount - 1)
            if (original.lastCheckpointId != expectedCheckpoint) {
                targetManifestConflict(
                    original.jobId,
                    "체크포인트가 manifest 처리 위치와 일치하지 않습니다."
                )
            }
        }

        val failedWithPositions = failedProblemIds.map { failedId ->
            val position = targetPosition(manifest, failedId)
                ?: targetManifestConflict(
                    original.jobId,
                    "실패 항목이 manifest에 없습니다. problemId=$failedId"
                )
            if (position >= original.processedCount) {
                targetManifestConflict(
                    original.jobId,
                    "실패 항목이 처리 완료 prefix 밖에 있습니다. problemId=$failedId"
                )
            }
            position to failedId
        }.sortedBy { it.first }
        val orderedFailedIds = failedWithPositions.map { it.second }

        if (original.status == JobStatus.COMPLETED) {
            if (original.processedCount != targetCount) {
                targetManifestConflict(
                    original.jobId,
                    "완료 작업의 처리 수가 대상 수와 일치하지 않습니다."
                )
            }
            return ManifestTargetSelection(
                explicitIds = orderedFailedIds,
                range = null
            )
        }

        val unprocessed = dropManifestTargets(manifest, original.processedCount)
        return ManifestTargetSelection(
            explicitIds = orderedFailedIds + unprocessed.explicitIds,
            range = unprocessed.range
        )
    }

    private fun loadManifestProblems(
        targetIds: List<String>,
        jobId: String
    ): List<Problem> {
        if (targetIds.isEmpty()) {
            return emptyList()
        }
        val foundProblems = problemRepository.findAllById(targetIds.toSet())
        val byId = foundProblems.associateBy { it.id.value }
        val missingCount = targetIds.count { targetId -> !byId.containsKey(targetId) }
        if (missingCount > 0) {
            log.info("retry skips deleted manifest problems: jobId=$jobId, missingCount=$missingCount")
        }
        return targetIds.mapNotNull(byId::get)
    }

    private fun metadataResponseRange(
        selected: ManifestTargetSelection
    ): JobRange {
        val explicitIds = selected.explicitIds.map { problemId ->
            problemId.toIntOrNull()
                ?: throw IllegalStateException("메타데이터 작업 대상 ID가 숫자가 아닙니다. problemId=$problemId")
        }
        val starts = explicitIds + listOfNotNull(selected.range?.start)
        val ends = explicitIds + listOfNotNull(selected.range?.end)
        return JobRange(
            start = starts.minOrNull()
                ?: throw IllegalStateException("메타데이터 재시도 대상이 없습니다."),
            end = ends.maxOrNull()
                ?: throw IllegalStateException("메타데이터 재시도 대상이 없습니다.")
        )
    }

    private fun loadRetryProblemCandidates(
        currentCandidates: List<Problem>,
        failedProblemIds: Set<String>,
        jobId: String
    ): List<Problem> {
        if (failedProblemIds.isEmpty()) {
            return orderProblems(currentCandidates)
        }

        val failedProblems = problemRepository.findAllById(failedProblemIds)
        val foundIds = failedProblems.mapTo(mutableSetOf()) { it.id.value }
        val missingIds = failedProblemIds - foundIds
        if (missingIds.isNotEmpty()) {
            log.info("retry skips deleted failed problems: jobId=$jobId, missingCount=${missingIds.size}")
        }
        return orderProblems(currentCandidates + failedProblems)
    }

    private fun selectRetryProblems(
        original: JobStatusUnifiedResponse,
        candidates: List<Problem>,
        failedProblemIds: Set<String>,
        checkpointExclusive: Int?,
        usesLegacyRetryFallback: Boolean
    ): List<Problem> {
        if (usesLegacyRetryFallback) {
            return orderProblems(candidates)
        }
        return orderProblems(
            candidates.filter { problem ->
                failedProblemIds.contains(problem.id.value) ||
                    (original.status != JobStatus.COMPLETED && isAfterCheckpoint(problem, checkpointExclusive))
            }
        )
    }
}
